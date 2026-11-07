package mdbms.optimizer.parser;

import java.util.List;

import mdbms.models.query.ExpressionNode;
import mdbms.optimizer.exception.ErrorCategory;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.lexer.Token;
import mdbms.optimizer.lexer.TokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionParserPrimaryTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void readsIntegerAndRealLiteralsWithoutLosingDigits() {
        TokenStream stream = stream("0 123456789012345678901234567890 3.1400");

        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.INTEGER_LITERAL, "0", null);
        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.INTEGER_LITERAL,
            "123456789012345678901234567890", null);
        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.REAL_LITERAL, "3.1400", null);
        assertTrue(stream.isAtEnd());
    }

    @Test
    void readsDecodedStringsIncludingEmptyStringsAndEscapedQuotes() {
        TokenStream stream = stream("'Ayu' '' 'it''s fine' 'baris\\nbaru'");

        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.STRING_LITERAL, "Ayu", null);
        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.STRING_LITERAL, "", null);
        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.STRING_LITERAL, "it's fine", null);
        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.STRING_LITERAL, "baris\nbaru", null);
        assertTrue(stream.isAtEnd());
    }

    @Test
    void readsBareAndQualifiedColumnsPreservingNames() {
        TokenStream stream = stream("Umur Siswa.Nama");

        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.COLUMN_REFERENCE, "Umur", null);
        assertEquals(1, stream.getPosition());
        assertLeaf(parser.parsePrimary(stream), ExpressionNode.Type.COLUMN_REFERENCE, "Nama", "Siswa");
        assertEquals(4, stream.getPosition());
        assertTrue(stream.isAtEnd());
    }

    @Test
    void leavesFollowingOperatorsAndDelimitersForTheCaller() {
        for (String sql : List.of("umur + 1", "siswa.umur >= 18", "18, 20", "'Ayu');")) {
            TokenStream stream = stream(sql);
            List<Token> tokens = stream.getTokens();
            int expectedPosition = sql.startsWith("siswa.") ? 3 : 1;

            parser.parsePrimary(stream);

            assertEquals(expectedPosition, stream.getPosition());
            assertSame(tokens.get(expectedPosition), stream.peek());
        }
    }

    @Test
    void rejectsMissingColumnAfterDotWithSourceLocationAndCollectedError() {
        for (String sql : List.of("siswa.", "siswa.*", "siswa.WHERE")) {
            QueryErrorCollector collector = new QueryErrorCollector();
            TokenStream stream = new ShaylaLexer(sql, collector).tokenizeToStream();
            Token invalid = stream.getTokens().get(2);

            QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
                () -> new ExpressionParser(sql, collector).parsePrimary(stream));

            assertDiagnostic(error, sql, invalid);
            assertSame(invalid, stream.peek());
            assertEquals(List.of(error), collector.getAllExceptions());
        }
    }

    @Test
    void rejectsUnsupportedPrimaryWithoutConsumingIt() {
        // A leading unary sign is not part of primary; parentheses are now supported.
        for (String sql : List.of("", "+", "*", "WHERE", "NULL", "-18", "\n  ;")) {
            QueryErrorCollector collector = new QueryErrorCollector();
            TokenStream stream = new ShaylaLexer(sql, collector).tokenizeToStream();
            Token invalid = stream.peek();

            QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
                () -> new ExpressionParser(sql, collector).parsePrimary(stream));

            assertDiagnostic(error, sql, invalid);
            assertEquals(0, stream.getPosition());
            assertSame(invalid, stream.peek());
            assertEquals(List.of(error), collector.getAllExceptions());
        }
    }

    @Test
    void emptyTokenStreamReportsEof() {
        TokenStream stream = new TokenStream(List.of());

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
            () -> parser.parsePrimary(stream));

        assertEquals(1, error.getLine());
        assertEquals(1, error.getColumn());
        assertEquals("", error.getLexeme());
        assertEquals(0, stream.getPosition());
    }

    private static TokenStream stream(String sql) {
        return new ShaylaLexer(sql).tokenizeToStream();
    }

    private static void assertLeaf(ExpressionNode node, ExpressionNode.Type type,
                                   String value, String tableName) {
        assertEquals(type, node.getType());
        assertEquals(value, node.getValue());
        if (tableName == null) {
            assertNull(node.getTableName());
        } else {
            assertEquals(tableName, node.getTableName());
        }
    }

    private static void assertDiagnostic(QuerySyntaxException error, String sql, Token token) {
        assertEquals(ErrorCategory.SYNTAX_ERROR, error.getCategory());
        assertEquals(token.getLine(), error.getLine());
        assertEquals(token.getColumn(), error.getColumn());
        assertEquals(token.getLexeme(), error.getLexeme());
        assertEquals(sql, error.getSqlSource());
    }
}
