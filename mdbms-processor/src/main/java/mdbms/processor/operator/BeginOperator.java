package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.processor.IResultRow;

public class BeginOperator implements IQueryOperator {
    private final IConcurrencyControl concurrencyControlManager;
    private int transactionId;
    private boolean isDone;
    private String message;

    public BeginOperator(IConcurrencyControl concurrencyControlManager) {
        this.concurrencyControlManager = concurrencyControlManager;
    }

    public void open() {
        isDone = false;
        message = null;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        transactionId = concurrencyControlManager.beginTransaction();
        // TODO BUTUH beginTransaction yang juga catat BEGIN ke FRM dari CCM
        // TODO BUTUH ClientSession menyimpan transactionId ini, dan ClientHandler gunakan untuk query berikutnya
        message = "BEGIN TRANSACTION " + transactionId;

        // TODO BUTUH ResultRow dari QP2 untuk return pesan konfirmasi
        return null;
    }

    public void close() {
    }

    public int getTransactionId() {
        return transactionId;
    }

    public String getMessage() {
        return message;
    }
}
