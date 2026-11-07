package mdbms.optimizer;

public class QuerySyntaxException extends QueryProcessingException {
    public QuerySyntaxException(String message, Token token, String sqlSource) {
        super(ErrorCategory.SYNTAX_ERROR, message, token, sqlSource);
    }

    public QuerySyntaxException(String message, int line, int column, String lexeme, String sqlSource) {
        super(ErrorCategory.SYNTAX_ERROR, message, line, column, lexeme, sqlSource);
    }
}
