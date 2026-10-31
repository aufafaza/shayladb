package mdbms.models;


public class Operation {
    private final Transaction transaction;
    private final OperationType type;
    private final DataItem dataItem;

    public Operation(Transaction transaction, OperationType type, DataItem dataItem) {
        this.transaction = transaction;
        this.type = type;
        this.dataItem = dataItem;
    }
    public Operation(Transaction transaction, OperationType type) {
        this(transaction, type, null);
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public OperationType getType() {
        return type;
    }

    public DataItem getDataItem() {
        return dataItem;
    }
}
