package mdbms.concurrency;

/** Result of asking the configured concurrency protocol for access. */
public enum AccessStatus {
    GRANTED,
    WAITING,
    ABORTED
}
