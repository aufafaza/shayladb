package mdbms.optimizer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ShaylaLexerTest {

    @Test
    void emptyAndWhitespaceInputReturnsOnlyEofInStream() {
        TokenStream emptyStream = new ShaylaLexer("").tokenizeToStream();
        assertTrue(emptyStream.isAtEnd());
        assertToken(emptyStream.peek(), TokenType.EOF, "", 1, 1);

        TokenStream whitespaceStream = new ShaylaLexer("   \n  \t  ").tokenizeToStream();
        assertTrue(whitespaceStream.isAtEnd());
        assertToken(whitespaceStream.peek(), TokenType.EOF, "", 2, 6);

        emptyStream.printAllParsedToken();
        whitespaceStream.printAllParsedToken();
    }

    @Test
    void tokenizesKeywordsCaseInsensitivelyAndPreservesLexemeCase() {
        String sql = "select FROM Where Insert into VALUES delete UPDATE set "
            + "CREATE table VIEW as INDEX on INT integer VARCHAR if EXISTS null PRIMARY key "
            + "START transaction BEGIN commit ROLLBACK not AND or DIV mod";

        TokenStream stream = new ShaylaLexer(sql).tokenizeToStream();

        Token selectToken = stream.expect(TokenType.selectTok);
        Token fromToken = stream.expect(TokenType.fromTok);
        Token whereToken = stream.expect(TokenType.whereTok);
        assertEquals("SELECT", selectToken.getLexeme());
        assertEquals("FROM", fromToken.getLexeme());
        assertEquals("WHERE", whereToken.getLexeme());

        TokenType[] remainingExpectedTypes = {
            TokenType.insertTok, TokenType.intoTok, TokenType.valuesTok,
            TokenType.deleteTok, TokenType.updateTok, TokenType.setTok,
            TokenType.createTok, TokenType.tableTok, TokenType.viewTok,
            TokenType.asTok, TokenType.indexTok, TokenType.onTok,
            TokenType.intTypeTok, TokenType.intTypeTok, TokenType.varcharTok,
            TokenType.ifTok, TokenType.existsTok, TokenType.nullTok,
            TokenType.primaryTok, TokenType.keyTok,
            TokenType.startTok, TokenType.transactionTok, TokenType.beginTok,
            TokenType.commitTok, TokenType.rollbackTok,
            TokenType.notTok, TokenType.andTok, TokenType.orTok,
            TokenType.idiv, TokenType.imod
        };

        for (TokenType expectedType : remainingExpectedTypes) {
            assertFalse(stream.isAtEnd());
            stream.expect(expectedType);
        }

        assertTrue(stream.isAtEnd());
        assertEquals(TokenType.EOF, stream.peek().getType());

        stream.printAllParsedToken();
    }

    @Test
    void tokenizesIdentifiersAndQualifiedNamesWithTokenStream() {
        String sql = "users.user_id _temp1 col2";
        TokenStream stream = new ShaylaLexer(sql).tokenizeToStream();

        assertToken(stream.expect(TokenType.idTok), TokenType.idTok, "users", 1, 1);
        assertToken(stream.expect(TokenType.period), TokenType.period, ".", 1, 6);
        assertToken(stream.expect(TokenType.idTok), TokenType.idTok, "user_id", 1, 7);
        assertToken(stream.expect(TokenType.idTok), TokenType.idTok, "_temp1", 1, 15);
        assertToken(stream.expect(TokenType.idTok), TokenType.idTok, "col2", 1, 22);
        assertTrue(stream.isAtEnd());
        assertToken(stream.peek(), TokenType.EOF, "", 1, 26);
    }

    @Test
    void tokenizesIntegerAndRealLiterals() {
        String sql = "0 42 3.14 100.005";
        TokenStream stream = new ShaylaLexer(sql).tokenizeToStream();

        assertToken(stream.advance(), TokenType.intLitTok, "0", 1, 1);
        assertToken(stream.advance(), TokenType.intLitTok, "42", 1, 3);
        assertToken(stream.advance(), TokenType.realLitTok, "3.14", 1, 6);
        assertToken(stream.advance(), TokenType.realLitTok, "100.005", 1, 11);
        assertTrue(stream.isAtEnd());
        assertToken(stream.peek(), TokenType.EOF, "", 1, 18);
    }

    @Test
    void tokenizesStringLiteralsWithoutEnclosingQuotes() {
        String sql = "'hello world' 'it''s fine' \"escaped\\nline\"";
        TokenStream stream = new ShaylaLexer(sql).tokenizeToStream();

        assertToken(stream.expect(TokenType.strTok), TokenType.strTok, "hello world", 1, 1);
        assertToken(stream.expect(TokenType.strTok), TokenType.strTok, "it's fine", 1, 15);
        assertToken(stream.expect(TokenType.strTok), TokenType.strTok, "escaped\nline", 1, 28);
        assertTrue(stream.isAtEnd());
        assertToken(stream.peek(), TokenType.EOF, "", 1, 43);
        stream.printAllParsedToken();
    }

    @Test
    void tokenizesOperatorsAndDelimiters() {
        String sql = "+ - * / % = != <> < <= > >= ( ) , ; . :";
        TokenStream stream = new ShaylaLexer(sql).tokenizeToStream();

        TokenType[] expectedTypes = {
            TokenType.plus, TokenType.minus, TokenType.star, TokenType.rdiv, TokenType.imod,
            TokenType.eql, TokenType.neq, TokenType.neq,
            TokenType.lss, TokenType.leq, TokenType.gtr, TokenType.geq,
            TokenType.lparent, TokenType.rparent, TokenType.comma,
            TokenType.semicolon, TokenType.period, TokenType.colon
        };

        for (int i = 0; i < expectedTypes.length; i++) {
            Token tok = stream.expect(expectedTypes[i]);
            if (i == 6) {
                assertEquals("!=", tok.getLexeme());
            } else if (i == 7) {
                assertEquals("<>", tok.getLexeme());
            }
        }
        assertTrue(stream.isAtEnd());
        stream.printAllParsedToken();
    }

    @Test
    void skipsSingleLineCommentsAndTracksLineAndColumnPositions() {
        String sql = "SELECT id -- select identifier\n"
            + "-- full line comment\n"
            + "FROM users WHERE age >= '%like';";

        TokenStream stream = new ShaylaLexer(sql).tokenizeToStream();

        assertToken(stream.expect(TokenType.selectTok), TokenType.selectTok, "SELECT", 1, 1);
        assertToken(stream.expect(TokenType.idTok), TokenType.idTok, "id", 1, 8);
        assertToken(stream.expect(TokenType.fromTok), TokenType.fromTok, "FROM", 3, 1);
        assertToken(stream.expect(TokenType.idTok), TokenType.idTok, "users", 3, 6);
        assertToken(stream.expect(TokenType.whereTok), TokenType.whereTok, "WHERE", 3, 12);
        assertToken(stream.expect(TokenType.idTok), TokenType.idTok, "age", 3, 18);
        assertToken(stream.expect(TokenType.geq), TokenType.geq, ">=", 3, 22);
        stream.printAllParsedToken();
    }

    @Test
    void unknownSymbolsProduceUnknownTokensAndRecordInErrorCollector() {
        QueryErrorCollector collector = new QueryErrorCollector();
        String sql = "SELECT @, ? FROM !users";
        ShaylaLexer sl = new ShaylaLexer(sql, collector);
        TokenStream stream = sl.tokenizeToStream();

        assertToken(stream.advance(), TokenType.selectTok, "SELECT", 1, 1);
        assertToken(stream.advance(), TokenType.UNKNOWN, "@", 1, 8);
        assertToken(stream.advance(), TokenType.comma, ",", 1, 9);
        assertToken(stream.advance(), TokenType.UNKNOWN, "?", 1, 11);
        assertToken(stream.advance(), TokenType.fromTok, "FROM", 1, 13);
        assertToken(stream.advance(), TokenType.UNKNOWN, "!", 1, 18);
        assertToken(stream.advance(), TokenType.idTok, "users", 1, 19);
        assertTrue(stream.isAtEnd());
        assertToken(stream.peek(), TokenType.EOF, "", 1, 24);

        assertTrue(collector.hasError());
        assertTrue(collector.hasFatalError());
        assertEquals(3, collector.getAllExceptions().size());
        assertEquals("@", collector.getAllExceptions().get(0).getLexeme());
        assertEquals(8, collector.getAllExceptions().get(0).getColumn());
        assertEquals("?", collector.getAllExceptions().get(1).getLexeme());
        assertEquals("!", collector.getAllExceptions().get(2).getLexeme());

        System.err.println(collector.getAllExceptions());
        
    }

    @Test
    void unterminatedStringThrowsQueryLexicalExceptionAndRecordsError() {
        QueryErrorCollector collector = new QueryErrorCollector();
        String sql = "SELECT 'unterminated FROM users";
        ShaylaLexer lexer = new ShaylaLexer(sql, collector);

        QueryLexicalException ex = assertThrows(QueryLexicalException.class, lexer::tokenizeToStream);

        assertEquals(ErrorCategory.LEXICAL_ERROR, ex.getCategory());
        assertEquals(1, ex.getLine());
        assertEquals(8, ex.getColumn());
        assertEquals("'unterminated FROM users", ex.getLexeme());
        assertEquals(sql, ex.getSqlSource());
        assertTrue(collector.hasError());
        assertTrue(collector.hasFatalError());
        assertEquals(1, collector.getAllExceptions().size());

        collector.printAllExpections();
    }

    @Test
    void malformedNumbersThrowQueryLexicalException() {
        List<String> invalidNumberQueries = List.of(
            "SELECT 12. FROM t",
            "SELECT 12.34.56 FROM t",
            "SELECT 123abc FROM t"
        );
        List<String> expectedLexemes = List.of("12.", "12.34.56", "123abc");

        for (int i = 0; i < invalidNumberQueries.size(); i++) {
            QueryErrorCollector collector = new QueryErrorCollector();
            String sql = invalidNumberQueries.get(i);
            ShaylaLexer lexer = new ShaylaLexer(sql, collector);

            assertFalse(collector.hasError());
            QueryLexicalException ex = assertThrows(QueryLexicalException.class, lexer::tokenizeToStream);
            assertEquals(ErrorCategory.LEXICAL_ERROR, ex.getCategory());
            assertEquals(1, ex.getLine());
            assertEquals(8, ex.getColumn());
            assertEquals(expectedLexemes.get(i), ex.getLexeme());
            assertEquals(sql, ex.getSqlSource());
            assertTrue(collector.hasFatalError());

            System.err.println(ex.getFormattedError());
        }

        
    }

    private static void assertToken(Token token, TokenType expectedType, String expectedLexeme, int expectedLine, int expectedCol) {
        assertEquals(expectedType, token.getType());
        assertEquals(expectedLexeme, token.getLexeme());
        assertEquals(expectedLine, token.getLine());
        assertEquals(expectedCol, token.getColumn());
    }
}
