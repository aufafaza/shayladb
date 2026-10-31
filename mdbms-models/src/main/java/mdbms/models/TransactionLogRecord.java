package mdbms.models;

import java.time.Instant;

public class TransactionLogRecord extends LogRecord {

    public TransactionLogRecord(long lsn, int txId, LogType type, Instant timestamp) {
        super(lsn, txId, type, timestamp);
    }
}
