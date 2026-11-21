package mdbms.concurrency;

public enum Locktype {
    SHARED,
    /** Table-range lock for writers that add rows: compatible with other INTENT_EXCLUSIVE, conflicts with SHARED. */
    INTENT_EXCLUSIVE,
    EXCLUSIVE
}
