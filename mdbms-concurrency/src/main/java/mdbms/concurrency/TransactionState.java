package mdbms.concurrency;

/** States in the lifecycle of a database transaction. */
public enum TransactionState {
    ACTIVE,
    PARTIALLY_COMMITTED,
    COMMITTED,
    FAILED,
    ABORTED
}
