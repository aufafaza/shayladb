package mdbms.optimizer;

import java.util.List;

import mdbms.models.query.ExpressionNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionParserPredicateTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void readsEveryComparisonAndNormalizesNotEqual() {
        for (String operator : List.of("=", "<>", ">", ">=", "<", "<=", "!=")) {
            TokenStream stream = stream("a " + operator + " 18");

            ExpressionNode root = parser.parsePredicate(stream).getRoot();

            assertEquals(ExpressionNode.Type.COMPARISON, root.getType());
            assertEquals(operator.equals("!=") ? "<>" : operator, root.getValue());
            assertEquals(ExpressionNode.Type.COLUMN_REFERENCE, root.getLeft().getType());
            assertEquals("a", root.getLeft().getValue());
            assertEquals(ExpressionNode.Type.INTEGER_LITERAL, root.getRight().getType());
            assertEquals("18", root.getRight().getValue());
            assertTrue(stream.isAtEnd());
        }
    }

    @Test
    void supportsQualifiedColumnsNumbersAndTextOnEitherSide() {
        assertPredicate("a.id = b.aid", "(= a.id b.aid)");
        assertPredicate("1.0500 < salary", "(< 1.0500 salary)");
        assertPredicate("nama = 'Ayu'", "(= nama Ayu)");
        assertPredicate("'Ayu' <> nama", "(<> Ayu nama)");
    }

    @Test
    void arithmeticBindsBeforeComparisonAndComparisonBeforeAnd() {
        assertPredicate("salary * 1.05 > 5000 AND umur >= 18",
            "(AND (> (* salary 1.05) 5000) (>= umur 18))");
        assertPredicate("a + b * 2 <= c / 3 - 1 AND x = y + 1",
            "(AND (<= (+ a (* b 2)) (- (/ c 3) 1)) (= x (+ y 1)))");
    }

    @Test
    void repeatedAndGroupsFromLeftToRight() {
        assertPredicate("a = 1 AND b = 2 AND c = 3",
            "(AND (AND (= a 1) (= b 2)) (= c 3))");
        assertPredicate("a = 1 and b = 2", "(AND (= a 1) (= b 2))");
    }

    @Test
    void parenthesesCanGroupEitherArithmeticOrConditions() {
        assertPredicate("(a + b) * 2 > c", "(> (* (+ a b) 2) c)");
        assertPredicate("(a + b > c)", "(> (+ a b) c)");
        assertPredicate("((a + b) > c)", "(> (+ a b) c)");
        assertPredicate("((a = 1 AND b = 2)) AND (c + 1 < 4)",
            "(AND (AND (= a 1) (= b 2)) (< (+ c 1) 4))");
    }

    @Test
    void explicitRightHandAndGroupingIsPreserved() {
        assertPredicate("a = 1 AND (b = 2 AND c = 3)",
            "(AND (= a 1) (AND (= b 2) (= c 3)))");
    }

    @Test
    void bothEntryPointsLeaveClauseAndDelimiterTokensUntouched() {
        String[] suffixes = {
            ")", ", 2", ";", "WHERE x = 1", "FROM siswa", "ORDER BY umur DESC",
            "order by umur", "JOIN b ON a.id = b.id", "NATURAL JOIN b"
        };
        for (boolean predicate : List.of(false, true)) {
            String prefix = predicate ? "(a + 1) > 2 AND b <= 5" : "(a + 1) * 2";
            int endPosition = stream(prefix).getTokens().size() - 1;
            for (String suffix : suffixes) {
                TokenStream stream = stream(prefix + " " + suffix);
                Token boundary = stream.getTokens().get(endPosition);

                if (predicate) {
                    parser.parsePredicate(stream);
                } else {
                    parser.parseExpression(stream);
                }

                assertEquals(endPosition, stream.getPosition(), suffix);
                assertSame(boundary, stream.peek(), suffix);
            }
        }
    }

    @Test
    void acceptsEofAndCanStartAfterAConsumedWhereKeyword() {
        TokenStream stream = stream("WHERE age >= 18 AND active = 1 ORDER BY age");
        stream.expect(TokenType.whereTok);

        ExpressionNode root = parser.parsePredicate(stream).getRoot();

        assertEquals("(AND (>= age 18) (= active 1))", shape(root));
        assertEquals("ORDER", stream.peek().getLexeme());
        assertPredicate("a = 1", "(= a 1)");
    }

    @Test
    void incompleteConditionsFailAtTheMissingOrUnexpectedOperand() {
        assertError("a >", true, 2);
        assertError("a > AND b = 1", true, 2);
        assertError("a = 1 AND", true, 4);
        assertError("a = 1 AND WHERE b = 2", true, 4);
        assertError("a + * b = 1", true, 2);
        assertError("(a = 1", true, 4);
        assertError("(a + 1", false, 4);
        assertError("a = 1 AND ()", true, 5);
        assertError("", true, 0);
    }

    @Test
    void diagnosticsRetainMultilineLocationAndSqlSource() {
        QuerySyntaxException error = assertError("a = 1 AND\n b >", true, 6);

        assertEquals(2, error.getLine());
        assertEquals(5, error.getColumn());
        assertEquals("", error.getLexeme());
    }

    @Test
    void adjacentOperandsAndStrayQualificationDotsAreRejected() {
        assertError("a = 1 b = 2", true, 3);
        assertError("a b", false, 1);
        assertError("1 2", false, 1);
        assertError("a.b.c", false, 3);
        assertError("a = b.c.d", true, 5);
        assertError("a = 1 ORDER umur", true, 3);
    }

    @Test
    void chainedComparisonsAreRejectedAtTheSecondOperator() {
        assertError("a < b < c", true, 3);
        assertError("a = b = c", true, 3);
        assertError("a = b >= c", true, 3);
    }

    @Test
    void predicatesRequireConditionsRatherThanBareValues() {
        for (String sql : List.of("a", "1", "'text'", "a + 1", "((a))")) {
            assertError(sql, true, 0);
        }
        assertError("a AND b = 1", true, 0);
        assertError("a = 1 AND b", true, 4);
    }

    @Test
    void conditionsCannotBeUsedAsArithmeticOrComparisonOperands() {
        assertError("(a = 1)", false, 0);
        assertError("(a = 1) + 2", false, 5);
        assertError("2 * (a = 1)", false, 2);
        assertError("a = (b = 1)", true, 2);
        assertError("(a = 1) = b", true, 5);
        assertError("(a = 1) * 2 > 0", true, 5);
    }

    @Test
    void unsupportedOperatorsFailInsteadOfReturningASuccessfulPrefix() {
        assertError("a = 1 OR b = 2", true, 3);
        assertError("NOT a = 1", true, 0);
        assertError("a = 1 NOT", true, 3);
        assertError("a MOD 2", false, 1);
        assertError("a DIV 2 = 1", true, 1);
    }

    @Test
    void contextualClausesAreBoundariesOnlyWhereTheyReallyStart() {
        assertError("a > ORDER BY umur", true, 2);
        assertError("salary + ORDER BY umur", false, 2);
        assertError("a = 1 AND JOIN b", true, 4);
        assertPredicate("ordering = 1", "(= ordering 1)");
        assertPredicate("order = 1", "(= order 1)");
        assertPredicate("a.ORDER = 1", "(= a.ORDER 1)");
        assertPredicate("'ORDER' = 'BY'", "(= ORDER BY)");
    }

    @Test
    void aParserCanBeReusedAfterErrorsAndBetweenEntryPoints() {
        assertThrows(QuerySyntaxException.class, () -> parser.parsePredicate(stream("a >")));
        assertPredicate("a = 1", "(= a 1)");
        TokenStream value = stream("(a + 1) * 2");
        assertEquals("(* (+ a 1) 2)", shape(parser.parseExpression(value).getRoot()));
        assertTrue(value.isAtEnd());
        assertThrows(QuerySyntaxException.class, () -> parser.parseExpression(stream("a +")));
        assertPredicate("b = 2 AND c = 3", "(AND (= b 2) (= c 3))");
    }

    @Test
    void explicitGroupClosingTokenBelongsToTheCallingParser() {
        TokenStream stream = stream("a = 1)");

        parser.parsePredicate(stream);

        stream.expect(TokenType.rparent);
        assertTrue(stream.isAtEnd());
        TokenStream extraClose = stream("(a = 1))");
        parser.parsePredicate(extraClose);
        assertEquals(TokenType.rparent, extraClose.peek().getType());
        // A statement caller expecting ';' or EOF will reject the extra ')'.
        assertThrows(QuerySyntaxException.class, () -> extraClose.expect(TokenType.semicolon));
    }

    @Test
    void predicateFactoriesKeepTheirKindsAndValidateArguments() {
        ExpressionNode a = ExpressionNode.column(null, "a");
        ExpressionNode one = ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1");
        ExpressionNode comparison = ExpressionNode.comparison("=", a, one);
        ExpressionNode and = ExpressionNode.logicalAnd(comparison, comparison);

        assertEquals(ExpressionNode.Type.COMPARISON, comparison.getType());
        assertEquals(ExpressionNode.Type.LOGICAL_AND, and.getType());
        assertSame(comparison, and.getLeft());
        assertSame(comparison, and.getRight());
        assertThrows(IllegalArgumentException.class, () -> ExpressionNode.comparison("+", a, one));
        assertThrows(NullPointerException.class, () -> ExpressionNode.comparison("=", null, one));
        assertThrows(NullPointerException.class, () -> ExpressionNode.logicalAnd(comparison, null));
        assertThrows(IllegalArgumentException.class,
            () -> ExpressionNode.literal(ExpressionNode.Type.COMPARISON, "="));
        assertThrows(IllegalArgumentException.class,
            () -> ExpressionNode.literal(ExpressionNode.Type.LOGICAL_AND, "AND"));
    }

    private void assertPredicate(String sql, String expectedShape) {
        TokenStream stream = stream(sql);

        ExpressionNode root = parser.parsePredicate(stream).getRoot();

        assertEquals(expectedShape, shape(root), sql);
        assertTrue(stream.isAtEnd(), sql);
    }

    private QuerySyntaxException assertError(String sql, boolean predicate, int offendingTokenIndex) {
        QueryErrorCollector collector = new QueryErrorCollector();
        TokenStream stream = new ShaylaLexer(sql, collector).tokenizeToStream();
        Token expected = stream.getTokens().get(offendingTokenIndex);
        ExpressionParser contextualParser = new ExpressionParser(sql, collector);

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class, () -> {
            if (predicate) {
                contextualParser.parsePredicate(stream);
            } else {
                contextualParser.parseExpression(stream);
            }
        }, sql);

        assertEquals(ErrorCategory.SYNTAX_ERROR, error.getCategory(), sql);
        assertEquals(expected.getLexeme(), error.getLexeme(), sql);
        assertEquals(expected.getLine(), error.getLine(), sql);
        assertEquals(expected.getColumn(), error.getColumn(), sql);
        assertEquals(sql, error.getSqlSource());
        assertEquals(List.of(error), collector.getAllExceptions(), sql);
        return error;
    }

    private static TokenStream stream(String sql) {
        return new ShaylaLexer(sql).tokenizeToStream();
    }

    private static String shape(ExpressionNode node) {
        return switch (node.getType()) {
            case BINARY_OPERATION, COMPARISON, LOGICAL_AND ->
                "(" + node.getValue() + " " + shape(node.getLeft()) + " " + shape(node.getRight()) + ")";
            default -> node.getTableName() == null ? node.getValue() : node.getTableName() + "." + node.getValue();
        };
    }
}
