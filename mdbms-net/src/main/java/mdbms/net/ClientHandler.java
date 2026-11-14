package mdbms.net;

import java.io.IOException;
import java.net.Socket;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;
import mdbms.models.query.ExecutionResult;

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
                String command = query.trim().split("[\\s;]+")[0].toUpperCase();
                if ((command.equals("COMMIT") || command.equals("ABORT")) && !session.isTransacting()) {
                    cli.printResult(new ExecutionResult(false, null, null, 0, "No active transaction"));
                    continue;
                }
                if (command.equals("BEGIN") && session.isTransacting()) {
                    cli.printResult(new ExecutionResult(false, null, null, 0,
                            "Transaction " + session.getCurrentTxId() + " is already active"));
                    continue;
                }
                boolean autoCommit = !session.isTransacting();
                int txId;
                try {
                    txId = autoCommit ? concurrencyControl.beginTransaction() : session.getCurrentTxId();
                } catch (IllegalStateException e) {
                    System.err.println("[ERROR] Could not begin transaction: " + e.getMessage());
                    continue;
                }
                try {
                    ExecutionResult result = processor.executeQuery(query, txId);
                    if (!result.success()) {
                        // QueryProcessor sudah membatalkan transaksinya
                        session.clearTransaction();
                    } else if (command.equals("BEGIN")) {
                        session.beginTransaction(txId);
                    } else if (command.equals("COMMIT") || command.equals("ABORT")) {
                        session.clearTransaction();
                    } else if (autoCommit) {
                        concurrencyControl.commitTransaction(txId);
                    }
                    cli.printResult(result);
                } catch (Exception e) {
                    try {
                        concurrencyControl.abortTransaction(txId, "Failed to execute query: " + e.getMessage());
                    } catch (Exception e2) {
                        System.err.println("[ERROR] Could not abort transaction " + txId + ": " + e.getMessage());
                    }
                    session.clearTransaction();
                    cli.printResult(new ExecutionResult(false, null, null, 0, e.getMessage()));
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
