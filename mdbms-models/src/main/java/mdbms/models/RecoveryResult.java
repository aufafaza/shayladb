package mdbms.models;

public class RecoveryResult {
    private final boolean success;
    private final long lsn;
    private final int maxTxId;
    private final String message;

    public RecoveryResult(boolean success, long lsn, int maxTxId, String message) {
        this.success = success;
        this.lsn = lsn;
        this.maxTxId = maxTxId;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getLsn() {
        return lsn;
    }

    public int getMaxTxId() {
        return maxTxId;
    }

    public String getMessage() {
        return message;
    }
}