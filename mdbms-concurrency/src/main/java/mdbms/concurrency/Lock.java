package mdbms.concurrency;

public class Lock {
    private Transaction tx;
    private DataItem data;
    private Locktype type;

    public Lock (Transaction Tx, DataItem Data, Locktype Type) {
        this.tx = Tx;
        this.data = Data;
        this.type = Type;
    }

    public Transaction getTransaction () {
        return this.tx;
    }

    public DataItem getDataItem () {
        return this.data;
    } 

    public Locktype getLocktype () {
        return type;
    }

    public void setTransaction (Transaction Tx) {
        this.tx = Tx;
    }

    public void setDataItem (DataItem Data) {
        this.data = Data;
    } 

    public void setLocktype (Locktype Type) {
        this.type = Type;
    }
     
}