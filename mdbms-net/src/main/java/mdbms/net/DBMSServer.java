package mdbms.net;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;

// Server TCP single use. Buat baru/restart kalau gagal bind atau setelah shutdown  
public class DBMSServer {
    public static final int DEFAULT_PORT = 3306;
    public static final int DEFAULT_POOL_SIZE = 10;

    public enum State { NEW, RUNNING, STOPPING, STOPPED, FAILED }

    private final int port;
    private final IQueryProcessor processor;
    private final IConcurrencyControl concurrencyControl;
    private final Set<Socket> activeClientSockets = ConcurrentHashMap.newKeySet();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);

    long shutdownTimeoutMs = 5_000L;
    private volatile State state = State.NEW;
    private volatile ServerSocket serverSocket;
    private volatile ExecutorService workerPool;

    public DBMSServer(int port, IQueryProcessor processor, IConcurrencyControl concurrencyControl) {
        this.port = port;
        this.processor = processor;
        this.concurrencyControl = concurrencyControl;
    }

    public void start() throws IOException {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException("DBMSServer cannot be started from state " + state);
            }
            try {
                serverSocket = new ServerSocket(port);
            } catch (IOException | RuntimeException e) {
                terminate(State.FAILED);
                throw e;
            }
            workerPool = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
            state = State.RUNNING;
            readyLatch.countDown();
        }

        SessionManager sessionManager = new SessionManager();
        IOException acceptError = null;
        try {
            while (state == State.RUNNING) {
                try {
                    dispatchClient(serverSocket.accept(), sessionManager);
                } catch (IOException e) {
                    if (state == State.RUNNING) acceptError = e;
                    break;
                }
            }
        } finally {
            closeActiveSockets();
            try {
                if (!workerPool.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                    workerPool.shutdownNow();
                    workerPool.awaitTermination(1_000L, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                serverSocket = null;
                terminate(State.STOPPED);
            }
            if (acceptError != null) throw acceptError;
        }
    }

    public void shutdown() throws IOException {
        IOException closeError = closeActiveSockets();
        try {
            stoppedLatch.await(shutdownTimeoutMs + 2_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closeError != null) throw closeError;
    }

    public boolean isRunning() { return state == State.RUNNING; }
    public State getState() { return state; }
    public int getBoundPort() {
        ServerSocket socket = serverSocket;
        return state == State.RUNNING && socket != null ? socket.getLocalPort() : -1;
    }
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit) && state == State.RUNNING;
    }
    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stoppedLatch.await(timeout, unit);
    }
    int getActiveClientCount() { return activeClientSockets.size(); }
    boolean isWorkerPoolTerminated() { return workerPool == null || workerPool.isTerminated(); }

    private void dispatchClient(Socket clientSocket, SessionManager sessionManager) {
        synchronized (this) {
            if (state != State.RUNNING) { closeQuietly(clientSocket); return; }
            activeClientSockets.add(clientSocket);
        }
        try {
            workerPool.execute(() -> {
                try {
                    new ClientHandler(clientSocket, sessionManager, processor, concurrencyControl).run();
                } finally {
                    releaseClient(clientSocket);
                }
            });
        } catch (RejectedExecutionException e) {
            releaseClient(clientSocket);
        }
    }

    private IOException closeActiveSockets() {
        ServerSocket socketToClose;
        List<Socket> clientsToClose;
        synchronized (this) {
            if (state == State.NEW) { terminate(State.STOPPED); return null; }
            if (state == State.STOPPED || state == State.FAILED) return null;
            state = State.STOPPING;
            socketToClose = serverSocket;
            clientsToClose = List.copyOf(activeClientSockets);
            activeClientSockets.clear();
            if (workerPool != null) workerPool.shutdown();
        }
        IOException closeError = null;
        try {
            if (socketToClose != null && !socketToClose.isClosed()) socketToClose.close();
        } catch (IOException e) {
            closeError = e;
        } finally {
            clientsToClose.forEach(DBMSServer::closeQuietly);
        }
        return closeError;
    }

    private void terminate(State finalState) {
        state = finalState;
        readyLatch.countDown();
        stoppedLatch.countDown();
    }

    private void releaseClient(Socket clientSocket) { activeClientSockets.remove(clientSocket); closeQuietly(clientSocket); }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) try { resource.close(); } catch (Exception ignored) {}
    }
}
