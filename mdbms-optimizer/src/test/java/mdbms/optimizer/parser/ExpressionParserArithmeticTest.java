package mdbms.optimizer.parser;

import java.util.List;

import mdbms.models.query.ExpressionNode;
import mdbms.optimizer.exception.ErrorCategory;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.lexer.TokenStream;
import mdbms.optimizer.lexer.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionParserArithmeticTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void multiplicationAndDivisionHaveHigherPriorityThanAdditionAndSubtraction() {
        assertExpression("5 + 3 * 2", "(+ 5 (* 3 2))");
        assertExpression("5 * 3 + 2", "(+ (* 5 3) 2)");
        assertExpression("20 - 6 / 3 + 4 * 2", "(+ (- 20 (/ 6 3)) (* 4 2))");
    }

    @Test
    void operatorsAtTheSameLevelGroupFromLeftToRight() {
        assertExpression("5 - 3 - 2", "(- (- 5 3) 2)");
        assertExpression("8 / 4 / 2", "(/ (/ 8 4) 2)");
        assertExpression("8 / 4 * 2", "(* (/ 8 4) 2)");
        assertExpression("8 - 4 + 2", "(+ (- 8 4) 2)");
    }

    @Test
    void parenthesesOverridePriorityAndAssociativity() {
        assertExpression("(5 + 3) * 2", "(* (+ 5 3) 2)");
        assertExpression("5 - (3 - 2)", "(- 5 (- 3 2))");
        assertExpression("8 / (4 / 2)", "(/ 8 (/ 4 2))");
    }

    @Test
    void nestedParenthesesReturnTheInnerTreeWithoutExtraNodes() {
        assertExpression("((5))", "5");
        assertExpression("((5 + 3) * (2 + 1))", "(* (+ 5 3) (+ 2 1))");

        TokenStream stream = stream("(umur) + 1");
        ExpressionNode primary = parser.parsePrimary(stream);

        assertEquals(ExpressionNode.Type.COLUMN_REFERENCE, primary.getType());
        assertEquals("umur", primary.getValue());
        assertEquals(TokenType.plus, stream.peek().getType());
    }

    @Test
    void arithmeticNodesPreserveColumnNamesAndLiteralText() {
        ExpressionNode root = parser.parseExpression(stream("Pegawai.Salary * 1.0500")).getRoot();

        assertEquals(ExpressionNode.Type.BINARY_OPERATION, root.getType());
        assertEquals("*", root.getValue());
        assertNull(root.getTableName());
        assertEquals(ExpressionNode.Type.COLUMN_REFERENCE, root.getLeft().getType());
        assertEquals("Pegawai", root.getLeft().getTableName());
        assertEquals("Salary", root.getLeft().getValue());
        assertEquals(ExpressionNode.Type.REAL_LITERAL, root.getRight().getType());
        assertEquals("1.0500", root.getRight().getValue());
    }

    @Test
    void valueExpressionsCanAlsoContainSingleLiteralsAndColumns() {
        for (String sql : List.of("42", "1.2500", "nama", "siswa.nama", "'Ayu'", "''")) {
            TokenStream stream = stream(sql);

            ExpressionNode root = parser.parseExpression(stream).getRoot();

            assertTrue(stream.isAtEnd());
            assertNull(root.getLeft());
            assertNull(root.getRight());
        }
    }

    @Test
    void leavesFollowingClausesComparisonAndDelimitersForTheCaller() {
        String[] inputs = {
            "salary * 1.05 WHERE id = 1", "salary + 1 > 5000", "5 + 3, 2",
            "5 + 3)", "5 + 3;", "5 + 3 AND id = 1"
        };
        TokenType[] expected = {
            TokenType.whereTok, TokenType.gtr, TokenType.comma,
            TokenType.rparent, TokenType.semicolon, TokenType.andTok
        };
        for (int i = 0; i < inputs.length; i++) {
            TokenStream stream = stream(inputs[i]);

            parser.parseExpression(stream);

            assertEquals(expected[i], stream.peek().getType(), inputs[i]);
        }
    }

    @Test
    void divisionBuildsATreeWithoutEvaluatingOrCheckingRuntimeValues() {
        assertExpression("1 / 0", "(/ 1 0)");
    }

    @Test
    void missingAndUnexpectedOperandsReportTheOffendingToken() {
        assertSyntaxError("5 +", 1, 4, "");
        assertSyntaxError("5 * / 2", 1, 5, "/");
        assertSyntaxError("a + * b", 1, 5, "*");
        assertSyntaxError("5 +\n * 2", 2, 2, "*");
        assertSyntaxError("", 1, 1, "");
    }

    @Test
    void emptyOrIncompleteParenthesesReportSyntaxErrors() {
        assertSyntaxError("()", 1, 2, ")");
        assertSyntaxError("(", 1, 2, "");
        assertSyntaxError("(5 + 3", 1, 7, "");
        assertSyntaxError("((5)", 1, 5, "");
        assertSyntaxError("(5 +)", 1, 5, ")");
        assertSyntaxError("(5 3)", 1, 4, "3");
    }

    @Test
    void unsupportedOperatorsAndUnarySignsAreNotSilentlyAddedToTheGrammar() {
        assertSyntaxError("-18", 1, 1, "-");
        assertSyntaxError("+18", 1, 1, "+");
        assertSyntaxError("5 MOD 2", 1, 3, "MOD");
    }

    @Test
    void binaryFactoryRequiresSupportedOperatorsAndBothOperands() {
        ExpressionNode one = ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1");

        assertThrows(IllegalArgumentException.class, () -> ExpressionNode.binary("AND", one, one));
        assertThrows(NullPointerException.class, () -> ExpressionNode.binary("+", null, one));
        assertThrows(NullPointerException.class, () -> ExpressionNode.binary("+", one, null));
        assertThrows(IllegalArgumentException.class,
            () -> ExpressionNode.literal(ExpressionNode.Type.BINARY_OPERATION, "+"));
    }

    private void assertExpression(String sql, String expectedShape) {
        TokenStream stream = stream(sql);

        var tree = parser.parseExpression(stream);
        ExpressionNode root = tree.getRoot();
        System.out.println("=== ExpressionTree for: " + sql + " ===");
        tree.printExpressionTree();

        assertEquals(expectedShape, shape(root), sql);
        assertTrue(stream.isAtEnd(), sql);
    }

    private void assertSyntaxError(String sql, int line, int column, String lexeme) {
        QueryErrorCollector collector = new QueryErrorCollector();
        TokenStream stream = new ShaylaLexer(sql, collector).tokenizeToStream();

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
            () -> new ExpressionParser(sql, collector).parseExpression(stream), sql);

        assertEquals(ErrorCategory.SYNTAX_ERROR, error.getCategory(), sql);
        assertEquals(line, error.getLine(), sql);
        assertEquals(column, error.getColumn(), sql);
        assertEquals(lexeme, error.getLexeme(), sql);
        assertEquals(sql, error.getSqlSource());
        assertEquals(List.of(error), collector.getAllExceptions(), sql);
    }

    private static TokenStream stream(String sql) {
        return new ShaylaLexer(sql).tokenizeToStream();
    }

    private static String shape(ExpressionNode node) {
        if (node.getType() == ExpressionNode.Type.BINARY_OPERATION) {
            return "(" + node.getValue() + " " + shape(node.getLeft()) + " " + shape(node.getRight()) + ")";
        }
        return node.getTableName() == null ? node.getValue() : node.getTableName() + "." + node.getValue();
    }
}
