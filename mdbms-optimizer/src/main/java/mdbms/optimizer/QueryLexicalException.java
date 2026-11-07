package mdbms.optimizer;

public class QueryLexicalException extends QueryProcessingException {
    public QueryLexicalException(String message, Token token, String sqlSource) {
        super(ErrorCategory.LEXICAL_ERROR, message, token, sqlSource);
    }

    public QueryLexicalException(String message, int line, int column, String lexeme, String sqlSource) {
        super(ErrorCategory.LEXICAL_ERROR, message, line, column, lexeme, sqlSource);
    }
}
