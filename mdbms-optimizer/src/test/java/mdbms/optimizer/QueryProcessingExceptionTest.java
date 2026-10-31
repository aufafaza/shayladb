package mdbms.optimizer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class QueryProcessingExceptionTest {

    @Test
    void tokenConstructorPreservesDiagnosticLocationAndMessage() {
        String sql = "SELECT\nFROM users";
        Token token = new Token(TokenType.fromTok, "FROM", 2, 1);

        QueryProcessingException exception = new QueryProcessingException(
            ErrorCategory.SYNTAX_ERROR, "Expected a projection", token, sql
        );

        assertEquals(ErrorCategory.SYNTAX_ERROR, exception.getCategory());
        assertEquals("Expected a projection", exception.getMessage());
        assertEquals(2, exception.getLine());
        assertEquals(1, exception.getColumn());
        assertEquals("FROM", exception.getLexeme());
        assertEquals(sql, exception.getSqlSource());
    }

    @Test
    void explicitLocationConstructorPreservesDiagnosticWithoutToken() {
        QueryProcessingException exception = new QueryProcessingException(
            ErrorCategory.LEXICAL_ERROR, "Unknown symbol", 3, 5, "@", "SELECT @"
        );

        assertEquals(ErrorCategory.LEXICAL_ERROR, exception.getCategory());
        assertEquals("Unknown symbol", exception.getMessage());
        assertEquals(3, exception.getLine());
        assertEquals(5, exception.getColumn());
        assertEquals("@", exception.getLexeme());
        assertEquals("SELECT @", exception.getSqlSource());
    }

    @Test
    void specializedExceptionsUseTheirOwnCategoryWithBothConstructors() {
        Token token = new Token(TokenType.UNKNOWN, "?", 1, 8);
        List<QueryProcessingException> tokenExceptions = List.of(
            new QueryLexicalException("Invalid query", token, "SELECT ?"),
            new QuerySyntaxException("Invalid query", token, "SELECT ?")
        );
        List<QueryProcessingException> locationExceptions = List.of(
            new QueryLexicalException("Invalid query", 1, 8, "?", "SELECT ?"),
            new QuerySyntaxException("Invalid query", 1, 8, "?", "SELECT ?")
        );
        List<ErrorCategory> categories = List.of(
            ErrorCategory.LEXICAL_ERROR, ErrorCategory.SYNTAX_ERROR
        );

        for (int i = 0; i < categories.size(); i++) {
            QueryProcessingException tokenException = tokenExceptions.get(i);
            QueryProcessingException locationException = locationExceptions.get(i);
            assertEquals(categories.get(i), tokenException.getCategory());
            assertEquals(categories.get(i), locationException.getCategory());
            assertEquals(tokenException.getMessage(), locationException.getMessage());
            assertEquals(tokenException.getLine(), locationException.getLine());
            assertEquals(tokenException.getColumn(), locationException.getColumn());
            assertEquals(tokenException.getLexeme(), locationException.getLexeme());
            assertEquals(tokenException.getSqlSource(), locationException.getSqlSource());
        }
    }
}
