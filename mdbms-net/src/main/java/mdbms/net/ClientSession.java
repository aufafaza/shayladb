package mdbms.net;

public class ClientSession {
    private String sessionId;
    private int currentTxId;
    private boolean isTransacting;

    public ClientSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getCurrentTxId() {
        return currentTxId;
    }

    public void beginTransaction(int txId) {
        currentTxId = txId;
        isTransacting = true;
    }

    public void clearTransaction() {
        currentTxId = 0;
        isTransacting = false;
    }

    public boolean isTransacting() {
        return isTransacting;
    }
}
