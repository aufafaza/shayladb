package mdbms.processor.exception;

public class AmbiguousColumnException extends QueryExecutionException {
    public AmbiguousColumnException(String columnName) {
        super("Ambiguous column name '" + columnName + "'.");
    }
}
