package mdbms.processor.exception;

// COMMIT atau ABORT ditolak CCM, CCM udah ngurus nasib transaksinya jadi pemanggil ga perlu abort lagi
public class TransactionCompletionException extends QueryExecutionException {
    public TransactionCompletionException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
