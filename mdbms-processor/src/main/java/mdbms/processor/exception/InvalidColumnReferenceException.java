package mdbms.processor.exception;

import mdbms.models.query.ColumnReference;

public class InvalidColumnReferenceException extends QueryExecutionException {
    public InvalidColumnReferenceException(ColumnReference columnReference) {
        super("Cannot reference column '" + columnReference + "'. ");
    }

    public InvalidColumnReferenceException(String columnName) {
        super("Cannot reference column'" + columnName + "'.");
    }

    public InvalidColumnReferenceException(String tableName, String columnName) {
        super("Cannot reference column '" + tableName + "." + columnName + "'. ");
    }
}
