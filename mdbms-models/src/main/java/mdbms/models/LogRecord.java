package mdbms.models;

import java.time.Instant;

public abstract class LogRecord {
    private final long lsn;
    private final int txId;
    private final LogType type;
    private final Instant timestamp;

    protected LogRecord(long lsn, int txId, LogType type, Instant timestamp) {
        this.lsn = lsn;
        this.txId = txId;
        this.type = type;
        this.timestamp = timestamp;
    }

    public long getLsn() {
        return lsn;
    }

    public int getTxId() {
        return txId;
    }

    public LogType getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
