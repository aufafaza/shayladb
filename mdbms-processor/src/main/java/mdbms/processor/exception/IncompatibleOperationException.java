package mdbms.processor.exception;

public class IncompatibleOperationException extends QueryExecutionException {
    public IncompatibleOperationException(String operator, String leftType, String rightType) {
        super("Operator '" + operator + "' cannot be applied to types '" + leftType + "' and '" + rightType + "'.");
    }
}
