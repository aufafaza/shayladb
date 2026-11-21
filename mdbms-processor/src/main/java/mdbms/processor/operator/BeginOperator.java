package mdbms.processor.operator;

import mdbms.processor.IResultRow;

@Deprecated 
public class BeginOperator implements IQueryOperator, IQueryMessage {
    private final int transactionId;
    private boolean isDone;
    private String message;

    @Deprecated 
    public BeginOperator(int transactionId) {
        this.transactionId = transactionId;
    }

    @Deprecated 
    public void open() {
        isDone = false;
    }

    @Deprecated 
    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        message = "BEGIN TRANSACTION " + transactionId;
        return null;
    }

    @Deprecated 
    public void close() {
    }

    @Deprecated 
    @Override
    public String getMessage() {
        return message;
    }
}
