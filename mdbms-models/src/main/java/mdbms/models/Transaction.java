package mdbms.models;

public class Transaction{
    private int id; 
    private long timestamp; 
    private TransactionState state; 
    public int getId(){ 
        return id; 
    }

    public TransactionState getState(){ 
        return state; 
    }

    public void setState(TransactionState state){
        this.state = state; 
    }

    public long getTimestamp(){ 
        return timestamp;
    }

}
