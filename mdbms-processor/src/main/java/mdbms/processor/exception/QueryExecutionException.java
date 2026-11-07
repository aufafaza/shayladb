package mdbms.processor.exception;

public class QueryExecutionException extends RuntimeException {
    public QueryExecutionException(String message) {
        super("Failed to execute query. " + message);
    }
}
