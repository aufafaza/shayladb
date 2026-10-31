package mdbms.net;

import java.io.IOException;
import java.net.Socket;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;
import mdbms.models.ExecutionResult;

public class ClientHandler implements Runnable {
    private Socket clientSocket;
    private IQueryProcessor processor;
    private SessionManager sessionManager;
    private ClientSession session;
    private IConcurrencyControl concurrencyControl;

    public ClientHandler(
            Socket clientSocket,
            SessionManager sessionManager,
            IQueryProcessor processor,
            IConcurrencyControl concurrencyControl) {
        this.clientSocket = clientSocket;
        this.processor = processor;
        this.sessionManager = sessionManager;
        this.session = sessionManager.newSession();
        this.concurrencyControl = concurrencyControl;
    }

    public void run() {
        try {
            CommandLineInterface cli = new CommandLineInterface(clientSocket);
            String query;
            while ((query = cli.readQuery()) != null) {
                int txId = concurrencyControl.beginTransaction();
                try {
                    session.beginTransaction(txId);
                    ExecutionResult result = processor.executeQuery(query, txId);
                    session.clearTransaction();
                    cli.printResult(result);
                } catch (Exception e) {
                    try {
                        concurrencyControl.abortTransaction(txId, "Failed to execute query: " + e.getMessage());
                    } catch (Exception e2) {
                        System.err.println("[ERROR] Could not abort transaction " + txId + ": " + e.getMessage());
                    }
                    session.clearTransaction();
                }
            }
        } catch (IOException e) {
            System.out.println("[ERROR] Client disconnected");
        } finally {
            if (session.isTransacting()) {
                int txId = session.getCurrentTxId();
                try {
                    concurrencyControl.abortTransaction(txId, "Client disconnected");
                } catch (Exception e) {
                    System.err.println("[ERROR] Could not abort transaction " + txId + ": " + e.getMessage());
                }
                session.clearTransaction();
            }

            sessionManager.removeSession(session.getSessionId());

            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("[ERROR] Could not close client socket: " + e.getMessage());
            }
        }
    }
}
