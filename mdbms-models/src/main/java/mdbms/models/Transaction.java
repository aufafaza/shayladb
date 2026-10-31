package mdbms.models;

import java.util.Objects;

public class Transaction{
    private final int id;
    private final long timestamp;
    private volatile TransactionState state;

    public Transaction(int id, long timestamp){
        if (id < 0) throw new IllegalArgumentException("id must not be negative");
        this.id = id;
        this.timestamp = timestamp;
        this.state = TransactionState.ACTIVE;
    }

    public int getId(){
        return id;
    }

    public TransactionState getState(){
        return state;
    }

    /* Only allows valid lifecycle transitions */
    public synchronized void setState(TransactionState next){
        Objects.requireNonNull(next, "next");
        if (state == next) return;
        if (!isValidTransition(state, next)) {
            throw new IllegalStateException("Cannot transition transaction " + id
                    + " from " + state + " to " + next);
        }
        state = next;
    }

    public long getTimestamp(){
        return timestamp;
    }

    private static boolean isValidTransition(TransactionState from, TransactionState to){
        return switch (from) {
            case ACTIVE -> to == TransactionState.PARTIALLY_COMMITTED || to == TransactionState.FAILED;
            case PARTIALLY_COMMITTED -> to == TransactionState.COMMITTED || to == TransactionState.FAILED;
            case FAILED -> to == TransactionState.ABORTED;
            case COMMITTED, ABORTED -> false;
        };
    }
}
