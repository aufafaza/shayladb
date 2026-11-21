package mdbms.net;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;

// Server TCP single use. Buat baru/restart kalau gagal bind atau setelah shutdown  
public class DBMSServer {
    public static final int DEFAULT_PORT = 7654;
    public static final int DEFAULT_POOL_SIZE = 10;
    public static final int DEFAULT_PENDING_CLIENTS = 10;

    public enum State {
        NEW, RUNNING, STOPPING, STOPPED, FAILED
    }

    private final int port;
    private final IQueryProcessor processor;
    private final IConcurrencyControl concurrencyControl;
    private final ClientHandler.Mode mode;
    private final Runnable checkpointAction;
    private final Set<Socket> activeClientSockets = ConcurrentHashMap.newKeySet();
    private final Map<Socket, ClientHandler> activeHandlers = new ConcurrentHashMap<>();
    private volatile SessionManager sessionManager;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);

    long shutdownTimeoutMs = 5_000L;
    private volatile State state = State.NEW;
    private volatile ServerSocket serverSocket;
    private volatile ThreadPoolExecutor workerPool;

    public DBMSServer(int port, IQueryProcessor processor, IConcurrencyControl concurrencyControl) {
        this(port, processor, concurrencyControl, ClientHandler.Mode.FRAMED_JSON);
    }

    public DBMSServer(int port, IQueryProcessor processor, IConcurrencyControl concurrencyControl,
            Runnable checkpointAction) {
        this(port, processor, concurrencyControl, ClientHandler.Mode.FRAMED_JSON, checkpointAction);
    }

    protected DBMSServer(int port, IQueryProcessor processor, IConcurrencyControl concurrencyControl,
            ClientHandler.Mode mode) {
        this(port, processor, concurrencyControl, mode, null);
    }

    protected DBMSServer(int port, IQueryProcessor processor, IConcurrencyControl concurrencyControl,
            ClientHandler.Mode mode, Runnable checkpointAction) {
        this.port = port;
        this.processor = processor;
        this.concurrencyControl = concurrencyControl;
        this.mode = mode;
        this.checkpointAction = checkpointAction;
    }

    public void start() throws IOException {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException("DBMSServer cannot be started from state " + state);
            }
            try {
                serverSocket = new ServerSocket(port);
                System.out.println("[INFO] DBMS Server successfully bind to port " + port);
            } catch (IOException | RuntimeException e) {
                terminate(State.FAILED);
                throw e;
            }
            workerPool = new ThreadPoolExecutor(
                    DEFAULT_POOL_SIZE,
                    DEFAULT_POOL_SIZE,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(DEFAULT_PENDING_CLIENTS),
                    r -> {
                        Thread thread = new Thread(r, "mdbms-net-client");
                        thread.setDaemon(false);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            sessionManager = new SessionManager();
            state = State.RUNNING;
            readyLatch.countDown();
        }

        IOException acceptError = null;
        try {
            while (state == State.RUNNING) {
                try {
                    dispatchClient(serverSocket.accept(), sessionManager);
                } catch (IOException e) {
                    if (state == State.RUNNING)
                        acceptError = e;
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
                terminate(workerPool.isTerminated() ? State.STOPPED : State.FAILED);
            }
            if (acceptError != null)
                throw acceptError;
        }
    }

    public void shutdown() throws IOException {
        IOException closeError = closeActiveSockets();
        try {
            if (!stoppedLatch.await(shutdownTimeoutMs + 2_000L, TimeUnit.MILLISECONDS)
                    || !isWorkerPoolTerminated()) {
                throw new IOException("Server shutdown timed out; workers are still active");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while awaiting server shutdown", e);
        }
        if (closeError != null)
            throw closeError;
    }

    /** Stops accepting connections without waiting for transaction cleanup. */
    public synchronized void stopAccepting() throws IOException {
        if (state == State.RUNNING) {
            state = State.STOPPING;
            serverSocket.close();
        } else if (state == State.NEW) {
            terminate(State.STOPPED);
        }
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public State getState() {
        return state;
    }

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

    public int getActiveClientCount() {
        return activeClientSockets.size();
    }

    public int getActiveSessionCount() {
        SessionManager manager = sessionManager;
        return manager == null ? 0 : manager.getSessionCount();
    }

    int getActiveHandlerCount() {
        return activeHandlers.size();
    }

    boolean isWorkerPoolTerminated() {
        return workerPool == null || workerPool.isTerminated();
    }

    private void dispatchClient(Socket clientSocket, SessionManager sessionManager) {
        synchronized (this) {
            if (state != State.RUNNING) {
                closeQuietly(clientSocket);
                return;
            }
            activeClientSockets.add(clientSocket);
        }
        try {
            System.out.println("[INFO] Client connected from: "
                    + clientSocket.getInetAddress() + ":" + clientSocket.getPort());
            clientSocket.setSoTimeout(FramedJsonProtocol.REQUEST_TIMEOUT_MILLIS);
            workerPool.execute(() -> {
                ClientHandler handler = null;
                try {
                    handler = new ClientHandler(clientSocket, sessionManager, processor, concurrencyControl, mode, checkpointAction);
                    activeHandlers.put(clientSocket, handler);
                    handler.run();
                } finally {
                    if (handler != null) {
                        activeHandlers.remove(clientSocket, handler);
                    }
                    releaseClient(clientSocket);
                }
            });
        } catch (RejectedExecutionException | IOException e) {
            activeHandlers.remove(clientSocket);
            if (e instanceof RejectedExecutionException && mode == ClientHandler.Mode.FRAMED_JSON) {
                try {
                    new FramedJsonProtocol(clientSocket, clientSocket.getInputStream())
                            .writeResponse(FramedJsonProtocol.Response.error(
                                    "CAPACITY_REACHED",
                                    "Server client capacity is exhausted",
                                    0));
                    System.out
                            .println("[INFO] Capacity reached. Rejected client from: "
                                    + clientSocket.getInetAddress()
                                    + ":" + clientSocket.getPort());
                } catch (IOException ignored) {
                    // The socket may already be unavailable.
                }
            }
            releaseClient(clientSocket);
        }
    }

    private IOException closeActiveSockets() {
        ServerSocket socketToClose;
        List<Socket> clientsToClose;
        synchronized (this) {
            if (state == State.NEW) {
                terminate(State.STOPPED);
                return null;
            }
            if (state == State.STOPPED || state == State.FAILED)
                return null;
            state = State.STOPPING;
            socketToClose = serverSocket;
            clientsToClose = List.copyOf(activeClientSockets);
            activeClientSockets.clear();
            if (workerPool != null)
                workerPool.shutdown();
        }
        IOException closeError = null;
        try {
            if (socketToClose != null && !socketToClose.isClosed())
                socketToClose.close();
        } catch (IOException e) {
            closeError = e;
        } finally {
            clientsToClose.forEach(socket -> {
                // The owning worker resolves its transaction in finally. Never block
                // the shutdown caller acquiring a transaction monitor here.
                closeQuietly(socket);
            });
        }
        return closeError;
    }

    private void terminate(State finalState) {
        System.out.println("[INFO] Server terminated");
        state = finalState;
        readyLatch.countDown();
        stoppedLatch.countDown();
    }

    private void releaseClient(Socket clientSocket) {
        try {
            System.out.println("[INFO] Releasing client from: "
                    + clientSocket.getInetAddress() + ":" + clientSocket.getPort());
        } finally {
            activeClientSockets.remove(clientSocket);
            closeQuietly(clientSocket);
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }
}