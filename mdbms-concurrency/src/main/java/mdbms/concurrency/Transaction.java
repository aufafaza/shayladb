package mdbms.concurrency;

import java.util.Objects;

/** Transaction identity and lifecycle state held in the transaction table. */
public final class Transaction {
    private final int id;
    private final long timestamp;
    private volatile TransactionState state;

    public Transaction(int id, long timestamp) {
        if (id < 0) throw new IllegalArgumentException("id must not be negative");
        this.id = id;
        this.timestamp = timestamp;
        this.state = TransactionState.ACTIVE;
    }

    public int getId() { return id; }

    public TransactionState getState() { return state; }

    public long getTimestamp() { return timestamp; }

    /**
     * Changes state only along valid lifecycle edges. The manager owns the
     * transition; this method is synchronized so readers cannot observe a
     * partially applied transition.
     */
    public synchronized void setState(TransactionState next) {
        Objects.requireNonNull(next, "next");
        if (state == next) return;
        if (!isValidTransition(state, next)) {
            throw new IllegalStateException("Cannot transition transaction " + id
                    + " from " + state + " to " + next);
        }
        state = next;
    }

    private static boolean isValidTransition(TransactionState from, TransactionState to) {
        return switch (from) {
            case ACTIVE -> to == TransactionState.PARTIALLY_COMMITTED || to == TransactionState.FAILED;
            case PARTIALLY_COMMITTED -> to == TransactionState.COMMITTED || to == TransactionState.FAILED;
            case FAILED -> to == TransactionState.ABORTED;
            case COMMITTED, ABORTED -> false;
        };
    }
}
