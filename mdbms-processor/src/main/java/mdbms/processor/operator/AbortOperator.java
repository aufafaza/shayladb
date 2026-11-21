package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.processor.IResultRow;

@Deprecated
public class AbortOperator implements IQueryOperator, IQueryMessage {
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private boolean isDone;
    private String message;

    @Deprecated
    public AbortOperator(IConcurrencyControl concurrencyControlManager, int transactionId) {
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
    }

    @Deprecated
    public void open() {
        isDone = false;
    }

    @Deprecated
    public IResultRow next() {
        if (isDone)
            return null;
        isDone = true;

        try {
            concurrencyControlManager.abortTransaction(transactionId, "ABORT requested by client");
            message = "ABORT transaction " + transactionId;
        } catch (RuntimeException e) {
            message = "ABORT failed: " + e.getMessage();
        }
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
