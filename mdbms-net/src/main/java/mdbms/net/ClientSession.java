package mdbms.net;

public class ClientSession {
    private final String sessionId;
    private int currentTxId;
    private boolean isTransacting;
    private boolean disconnected;
    private int inFlightTxId;

    public ClientSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public synchronized String getSessionId() {
        return sessionId;
    }

    public synchronized int getCurrentTxId() {
        return currentTxId;
    }

    public synchronized void beginTransaction(int txId) {
        currentTxId = txId;
        isTransacting = true;
        inFlightTxId = txId;
    }

    public synchronized void clearTransaction() {
        currentTxId = 0;
        isTransacting = false;
        inFlightTxId = 0;
    }

    public synchronized boolean isTransacting() {
        return isTransacting;
    }

    public synchronized void markInFlight(int txId) {
        inFlightTxId = txId;
    }

    public synchronized int getInFlightTxId() {
        return inFlightTxId;
    }

    public synchronized boolean isDisconnected() {
        return disconnected;
    }

    public synchronized void markDisconnected() {
        disconnected = true;
    }
}
