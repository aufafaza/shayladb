package mdbms.processor.operator;

import mdbms.processor.IResultRow;

public class BeginOperator implements IQueryOperator, IQueryMessage {
    private final int transactionId;
    private boolean isDone;
    private String message;

    public BeginOperator(int transactionId) {
        this.transactionId = transactionId;
    }

    public void open() {
        isDone = false;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        message = "BEGIN TRANSACTION " + transactionId;
        return null;
    }

    public void close() {
    }

    @Override
    public String getMessage() {
        return message;
    }
}
