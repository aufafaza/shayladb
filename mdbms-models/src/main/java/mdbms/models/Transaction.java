package mdbms.models;

public record Transaction(int id, TransactionState state, long timestamp) {
    public int getId(){ 
        return id; 
    }

    public TransactionState getTransactionState(){ 
        return state; 
    }

    public long getTimestamp(){ 
        return timestamp;
    }

}
