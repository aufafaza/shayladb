package mdbms.models;

public class Transaction{
    private final int id;
    private final long timestamp;
    private TransactionState state;

    public Transaction(int id, long timestamp){
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

    public void setState(TransactionState state){
        this.state = state;
    }

    public long getTimestamp(){
        return timestamp;
    }

}
