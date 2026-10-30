package mdbms.concurrency;

/** Minimal log entry needed by the CC1/FRM boundary. */
public record LogRecord(int txId, String eventType, String tableName, String rowId,
                        Object oldValue, Object newValue) {
}
