package mdbms.processor.exception;

public class UnexpectedTypeException extends QueryExecutionException {
    public UnexpectedTypeException(String expectedType, String actualType) {
        super("Expected type '" + expectedType + "' but instead got '" + actualType + "'.");
    }
}
