package mdbms.net;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;

public class DBMSServer {
    public static final int DEFAULT_PORT = 3306;
    public static final int DEFAULT_POOL_SIZE = 10;

    private int port;
    private ServerSocket serverSocket;
    private boolean isRunning;

    private IQueryProcessor processor;
    private IConcurrencyControl concurrencyControl;

    public DBMSServer(int port, IQueryProcessor processor, IConcurrencyControl concurrencyControl) {
        this.port = port;
        this.processor = processor;
        this.concurrencyControl = concurrencyControl;
    }

    public void start() throws IOException {
        ExecutorService workerPool = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
        SessionManager sessionManager = new SessionManager();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[INFO] DBMS Server successfully bind to port " + port);
            System.out.println("[INFO] Waiting for connection...");

            while (isRunning) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[INFO] Client connected from: " + clientSocket.getRemoteSocketAddress());

                workerPool.execute(new ClientHandler(
                        clientSocket, sessionManager, processor, concurrencyControl));
            }
        } catch (BindException e) {
            System.err.println("[ERROR] Port " + port
                    + " already in use.");
        } catch (IOException e) {
            System.err.println("[ERROR] IOException on server socket: " + e.getMessage());
        } finally {
            workerPool.shutdown();
            System.out.println("[INFO] Server shutdowned.");
        }
    }

    public void shutdown() throws IOException {
        serverSocket.close();
        isRunning = false;
    }

    public boolean isRunning() {
        return isRunning;
    }
}
