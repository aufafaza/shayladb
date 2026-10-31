package mdbms.optimizer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class QueryErrorCollectorTest {

    @Test
    void newCollectorIsInitiallyEmpty() {
        QueryErrorCollector collector = new QueryErrorCollector();

        assertFalse(collector.hasError());
        assertFalse(collector.hasFatalError());
        assertTrue(collector.getAllExceptions().isEmpty());
    }

    @Test
    void collectsLexicalAndSyntaxExceptionsInOrderAndFlagsFatalError() {
        QueryErrorCollector collector = new QueryErrorCollector();
        QueryLexicalException lexicalException = new QueryLexicalException(
            "Unknown symbol", 1, 8, "@", "SELECT @"
        );
        QuerySyntaxException syntaxException = new QuerySyntaxException(
            "Unexpected token", 1, 10, "FROM", "SELECT @ FROM"
        );

        collector.addException(lexicalException);
        collector.addException(syntaxException);

        assertTrue(collector.hasError());
        assertTrue(collector.hasFatalError());

        List<QueryProcessingException> exceptions = collector.getAllExceptions();
        assertEquals(2, exceptions.size());
        assertSame(lexicalException, exceptions.get(0));
        assertSame(syntaxException, exceptions.get(1));
    }

    @Test
    void hasFatalErrorIsTrueForSyntaxErrorAlone() {
        QueryErrorCollector collector = new QueryErrorCollector();
        collector.addException(new QuerySyntaxException("Missing table name", 1, 13, ";", "SELECT * FROM ;"));

        assertTrue(collector.hasError());
        assertTrue(collector.hasFatalError());
    }

    @Test
    void nonFatalCategorySetsHasErrorWithoutHasFatalError() {
        QueryErrorCollector collector = new QueryErrorCollector();
        QueryProcessingException nonFatal = new QueryProcessingException(
            null, "Non-fatal warning", 1, 1, "SELECT", "SELECT 1"
        );

        collector.addException(nonFatal);

        assertTrue(collector.hasError());
        assertFalse(collector.hasFatalError());
        assertEquals(1, collector.getAllExceptions().size());
    }

    @Test
    void ignoresNullExceptionAndReturnsUnmodifiableList() {
        QueryErrorCollector collector = new QueryErrorCollector();
        collector.addException(null);

        assertFalse(collector.hasError());
        assertFalse(collector.hasFatalError());
        assertTrue(collector.getAllExceptions().isEmpty());

        QueryLexicalException lexicalException = new QueryLexicalException(
            "Bad token", 1, 1, "#", "#"
        );
        collector.addException(lexicalException);

        List<QueryProcessingException> all = collector.getAllExceptions();
        assertThrows(UnsupportedOperationException.class, () -> all.add(lexicalException));
    }
}
