package mdbms.net;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;
import mdbms.models.query.ExecutionResult;

/**
 * Executes one connection using either the interactive telnet or framed raw
 * protocol.
 */
public class ClientHandler implements Runnable {
    enum Mode {
        FRAMED_JSON,
        INTERACTIVE
    }

    private final Socket clientSocket;
    private final IQueryProcessor processor;
    private final SessionManager sessionManager;
    private final ClientSession session;
    private final IConcurrencyControl concurrencyControl;
    private final Mode mode;

    ClientHandler(
            Socket clientSocket,
            SessionManager sessionManager,
            IQueryProcessor processor,
            IConcurrencyControl concurrencyControl) {
        this(clientSocket, sessionManager, processor, concurrencyControl, Mode.FRAMED_JSON);
    }

    public ClientHandler(
            Socket clientSocket,
            SessionManager sessionManager,
            IQueryProcessor processor,
            IConcurrencyControl concurrencyControl,
            Mode mode) {
        this.clientSocket = clientSocket;
        this.processor = processor;
        this.sessionManager = sessionManager;
        this.session = sessionManager.newSession();
        this.concurrencyControl = concurrencyControl;
        this.mode = mode;
    }

    @Override
    public void run() {
        try {
            if (mode == Mode.INTERACTIVE) {
                try (TelnetHandler transport = new TelnetHandler(clientSocket)) {
                    runTelnet(new CommandLineInterface(clientSocket, transport));
                }
            } else {
                runFramed(new FramedJsonProtocol(clientSocket, clientSocket.getInputStream()));
            }
        } catch (IOException e) {
            System.out.println("[ERROR] Client disconnected");
        } finally {
            closeSession();
        }
    }

    private void runTelnet(CommandLineInterface cli) throws IOException {
        clientSocket.setSoTimeout(CommandLineInterface.TELNET_REQUEST_TIMEOUT_MILLIS);
        try {
            String query;
            while ((query = cli.readQuery()) != null) {
                if (!executeTelnetQuery(cli, query)) {
                    return;
                }
            }
        } catch (ProtocolException e) {
            cli.printResult(new ExecutionResult(false, null, null, -1,
                    "Error: " + FramedJsonProtocol.messageOrDefault(e.getMessage(), "Telnet request rejected")));
        } catch (SocketTimeoutException e) {
            cli.printResult(new ExecutionResult(false, null, null, 0,
                    "Error: request timed out after " + CommandLineInterface.TELNET_REQUEST_TIMEOUT_MILLIS / 1000
                            + " seconds"));
        }
    }

    private void runFramed(FramedJsonProtocol protocol) throws IOException {
        while (true) {
            String query;
            try {
                query = protocol.readSql();
            } catch (ProtocolException e) {
                protocol.writeResponse(FramedJsonProtocol.Response.error(e.code(),
                        FramedJsonProtocol.messageOrDefault(e.getMessage(), "Invalid request"), 0));
                if (e.closeConnection()) {
                    return;
                }
                continue;
            }
            if (query == null || !executeFramedQuery(protocol, query)) {
                return;
            }
        }
    }

    private boolean executeTelnetQuery(CommandLineInterface cli, String query) {
        String command = commandOf(query);
        if (command.isEmpty()) {
            return true;
        }
        if ((command.equals("COMMIT") || command.equals("ABORT")) && !session.isTransacting()) {
            cli.printResult(new ExecutionResult(false, null, null, -1, "No active transaction"));
            return true;
        }
        if (command.equals("BEGIN") && session.isTransacting()) {
            cli.printResult(new ExecutionResult(false, null, null, -1,
                    "Transaction " + session.getCurrentTxId() + " is already active"));
            return true;
        }
        if (command.equals("EXIT") || command.equals("QUIT")) {
            cli.printResult(new ExecutionResult(true, null, null, -1, "Goodbye"));
            return false;
        }

        long started = System.nanoTime();
        ExecutionResult result = executeQuery(query, command);
        cli.printExecutionTime(System.nanoTime() - started);
        cli.printResult(result);
        return true;
    }

    private boolean executeFramedQuery(FramedJsonProtocol protocol, String query) throws IOException {
        String command = commandOf(query);
        if (command.isEmpty()) {
            protocol.writeResponse(FramedJsonProtocol.Response.error("INVALID_REQUEST", "SQL must not be blank", 0));
            return true;
        }
        if ((command.equals("COMMIT") || command.equals("ABORT")) && !session.isTransacting()) {
            protocol.writeResponse(FramedJsonProtocol.Response.error("TRANSACTION_STATE", "No active transaction", 0));
            return true;
        }
        if (command.equals("BEGIN") && session.isTransacting()) {
            protocol.writeResponse(FramedJsonProtocol.Response.error("TRANSACTION_STATE",
                    "Transaction " + session.getCurrentTxId() + " is already active", 0));
            return true;
        }
        if (command.equals("EXIT") || command.equals("QUIT")) {
            protocol.writeResponse(FramedJsonProtocol.Response.ok("Connection closed", 0));
            return false;
        }

        long started = System.nanoTime();
        ExecutionResult result = executeQuery(query, command);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        protocol.writeResponse(FramedJsonProtocol.fromExecutionResult(result, elapsedMs));
        return true;
    }

    private ExecutionResult executeQuery(String query, String command) {
        if (command.equals("BEGIN")) {
            return beginTransaction();
        }
        if (command.equals("COMMIT")) {
            return commitTransaction();
        }
        if (command.equals("ABORT")) {
            return abortTransaction();
        }
        return executeStatement(query);
    }

    private ExecutionResult beginTransaction() {
        try {
            int txId = concurrencyControl.beginTransaction();
            session.beginTransaction(txId);
            return new ExecutionResult(true, null, null, -1, "BEGIN transaction " + txId);
        } catch (RuntimeException e) {
            return new ExecutionResult(false, null, null, 0,
                    "Transaction admission rejected: "
                            + FramedJsonProtocol.messageOrDefault(e.getMessage(), "unavailable"));
        }
    }

    private ExecutionResult commitTransaction() {
        int txId = session.getCurrentTxId();
        try {
            concurrencyControl.commitTransaction(txId);
            session.clearTransaction();
            return new ExecutionResult(true, null, null, -1, "COMMIT transaction " + txId);
        } catch (RuntimeException e) {
            session.clearTransaction();
            return new ExecutionResult(false, null, null, 0,
                    "COMMIT failed: " + FramedJsonProtocol.messageOrDefault(e.getMessage(), "unable to commit transaction"));
        }
    }

    private ExecutionResult abortTransaction() {
        int txId = session.getCurrentTxId();
        try {
            concurrencyControl.abortTransaction(txId, "ABORT requested by client");
            session.clearTransaction();
            return new ExecutionResult(true, null, null, -1, "ABORT transaction " + txId);
        } catch (RuntimeException e) {
            session.clearTransaction();
            return new ExecutionResult(false, null, null, 0,
                    "ABORT failed: " + FramedJsonProtocol.messageOrDefault(e.getMessage(), "unable to abort transaction"));
        }
    }

    private ExecutionResult executeStatement(String query) {
        boolean autoCommit = !session.isTransacting();
        int txId;
        try {
            txId = autoCommit ? concurrencyControl.beginTransaction() : session.getCurrentTxId();
        } catch (RuntimeException e) {
            return new ExecutionResult(false, null, null, 0,
                    "Transaction admission rejected: "
                            + FramedJsonProtocol.messageOrDefault(e.getMessage(), "unavailable"));
        }
        if (autoCommit) {
            session.beginTransaction(txId);
        }
        session.markInFlight(txId);

        try {
            ExecutionResult result = processor.executeQuery(query, txId);
            if (session.isDisconnected()) {
                session.clearTransaction();
                return result == null
                        ? new ExecutionResult(false, null, null, 0, "Client disconnected")
                        : result;
            }
            if (result == null) {
                RuntimeException rollbackFailure = abortIfActive(txId, "Query processor returned no result");
                session.clearTransaction();
                if (rollbackFailure != null) {
                    return new ExecutionResult(false, null, null, 0,
                            "ROLLBACK failed: " + rollbackFailure.getMessage());
                }
                return new ExecutionResult(false, null, null, 0, "Query processor returned no result");
            }
            if (!result.success()) {
                RuntimeException rollbackFailure = abortIfActive(txId, "Query execution failed");
                session.clearTransaction();
                if (rollbackFailure != null) {
                    return new ExecutionResult(false, null, null, 0,
                            "ROLLBACK failed: " + rollbackFailure.getMessage());
                }
                return result;
            }

            if (autoCommit) {
                try {
                    concurrencyControl.commitTransaction(txId);
                    session.clearTransaction();
                } catch (RuntimeException e) {
                    session.clearTransaction();
                    return new ExecutionResult(false, null, null, 0,
                            "COMMIT failed: " + FramedJsonProtocol.messageOrDefault(e.getMessage(), "unable to commit transaction"));
                }
            }
            return result;
        } catch (RuntimeException e) {
            RuntimeException rollbackFailure = abortIfActive(txId, "Failed to execute query: " + e.getMessage());
            session.clearTransaction();
            if (rollbackFailure != null) {
                return new ExecutionResult(false, null, null, 0,
                        "ROLLBACK failed: " + rollbackFailure.getMessage());
            }
            return new ExecutionResult(false, null, null, 0,
                    FramedJsonProtocol.messageOrDefault(e.getMessage(), "Query execution failed"));
        }
    }

    private String commandOf(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("[\\s;]+", 2);
        return parts[0].toUpperCase();
    }

    private RuntimeException abortIfActive(int txId, String reason) {
        try {
            concurrencyControl.abortTransaction(txId, reason);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    public void disconnect() {
        session.markDisconnected();
        int txId = session.getInFlightTxId();
        if (txId == 0) {
            txId = session.isTransacting() ? session.getCurrentTxId() : 0;
        }
        if (txId != 0) {
            try {
                concurrencyControl.abortTransaction(txId, "Client disconnected");
            } catch (RuntimeException e) {
                System.err.println("[ERROR] Could not abort transaction " + txId + ": " + e.getMessage());
            } finally {
                session.clearTransaction();
            }
        }
        try {
            clientSocket.close();
        } catch (Exception ignored) {
            // Socket cleanup is best-effort; transaction resolution is attempted above.
        }
    }

    private void closeSession() {
        disconnect();

        sessionManager.removeSession(session.getSessionId());
        try {
            clientSocket.close();
        } catch (IOException e) {
            System.err.println("[ERROR] Could not close client socket: " + e.getMessage());
        }
    }
}
