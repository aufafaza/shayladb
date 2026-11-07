package mdbms.optimizer.exception;

public class QuerySemanticException extends QueryProcessingException {
    public QuerySemanticException(String message) {
        super(ErrorCategory.SEMANTIC_ERROR, message, 0, 0, "", "");
    }

    public QuerySemanticException(String message, String problematicItem) {
        super(ErrorCategory.SEMANTIC_ERROR, message, 0, 0, problematicItem != null ? problematicItem : "", "");
    }
}

