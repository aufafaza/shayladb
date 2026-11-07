package mdbms.processor.exception;

public class TransactionAbortedException extends QueryExecutionException {
    public TransactionAbortedException(int txId) {
        super("Transaction " + txId + " was aborted by Concurrency Control Manager.");
    }
}
