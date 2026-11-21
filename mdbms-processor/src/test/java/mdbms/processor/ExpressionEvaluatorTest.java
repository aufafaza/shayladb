package mdbms.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.processor.exception.UnexpectedTypeException;
import mdbms.processor.mocks.MockOperator;
import org.junit.jupiter.api.Test;

public class ExpressionEvaluatorTest {

    @Test
    void evaluatesStringIntegerAndRealLiterals() {
        assertEquals("Ada", evaluate(literal(ExpressionNode.Type.STRING_LITERAL, "Ada")));
        assertEquals(42L, evaluate(literal(ExpressionNode.Type.INTEGER_LITERAL, "42")));
        assertEquals(3.25d, evaluate(literal(ExpressionNode.Type.REAL_LITERAL, "3.25")));
    }

    @Test
    void evaluatesColumnReferences() {
        IResultRow row = row("student", "id", 7L);

        assertEquals(7L, evaluate(ExpressionNode.column("student", "id"), row));
        assertEquals(7L, evaluate(ExpressionNode.column(null, "id"), row));
    }

    @Test
    void evaluatesIntegerArithmetic() {
        assertEquals(7L, evaluate(binary("+", integer("3"), integer("4"))));
        assertEquals(-1L, evaluate(binary("-", integer("3"), integer("4"))));
        assertEquals(12L, evaluate(binary("*", integer("3"), integer("4"))));
        assertEquals(0.75d, evaluate(binary("/", integer("3"), integer("4"))));
    }

    @Test
    void evaluatesRealAndStringArithmetic() {
        assertEquals(7.5d, evaluate(binary("+", real("3.25"), real("4.25"))));
        assertEquals("HelloWorld", evaluate(binary("+",
                literal(ExpressionNode.Type.STRING_LITERAL, "Hello"),
                literal(ExpressionNode.Type.STRING_LITERAL, "World"))));
        assertEquals(2.0d, evaluate(binary("-", integer("5"), real("3"))));
        assertEquals(15.0d, evaluate(binary("*", real("3"), integer("5"))));
    }

    @Test
    void rejectsInvalidArithmeticOperands() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> evaluate(binary("+", literal(ExpressionNode.Type.STRING_LITERAL, "A"), integer("1"))));
        assertEquals("Failed to evaluate expression: invalid plus operation", failure.getMessage());
    }

    @Test
    void evaluatesAllComparisonOperators() {
        assertTrue((Boolean) evaluate(comparison("=", integer("4"), integer("4"))));
        assertTrue((Boolean) evaluate(comparison("<>", integer("4"), integer("5"))));
        assertTrue((Boolean) evaluate(comparison(">", integer("5"), integer("4"))));
        assertTrue((Boolean) evaluate(comparison(">=", integer("5"), integer("5"))));
        assertTrue((Boolean) evaluate(comparison("<", integer("4"), integer("5"))));
        assertTrue((Boolean) evaluate(comparison("<=", integer("5"), integer("5"))));

        assertFalse((Boolean) evaluate(comparison("=", literal(ExpressionNode.Type.STRING_LITERAL, "Ada"),
                literal(ExpressionNode.Type.STRING_LITERAL, "Budi"))));
    }

    @Test
    void comparesFloatColumnsUsingFloatPrecision() {
        IResultRow row = row("product", "score", 0.1f);

        assertTrue((Boolean) evaluate(comparison("=",
                ExpressionNode.column("product", "score"),
                literal(ExpressionNode.Type.REAL_LITERAL, "0.1")), row));
    }

    @Test
    void evaluatesLogicalAnd() {
        assertTrue((Boolean) evaluate(ExpressionNode.logicalAnd(
                comparison(">", integer("5"), integer("2")),
                comparison("=", integer("3"), integer("3")))));
        assertFalse((Boolean) evaluate(ExpressionNode.logicalAnd(
                comparison(">", integer("5"), integer("9")),
                comparison("=", integer("3"), integer("3")))));
    }

    @Test
    void rejectsLogicalAndWithNonBooleanOperands() {
        assertThrows(RuntimeException.class, () -> evaluate(ExpressionNode.logicalAnd(
                integer("1"), comparison("=", integer("1"), integer("1")))));
    }

    @Test
    void matchesEveryConditionAndReturnsTrueForEmptyConditions() {
        IResultRow row = row("student", "grade", 85);

        assertTrue(ExpressionEvaluator.matches(List.of(), row));
        assertTrue(ExpressionEvaluator.matches(List.of(
                new ExpressionTree(comparison(">", ExpressionNode.column("student", "grade"), integer("80"))),
                new ExpressionTree(comparison("<=", ExpressionNode.column("student", "grade"), integer("90")))), row));
        assertFalse(ExpressionEvaluator.matches(List.of(
                new ExpressionTree(comparison(">", ExpressionNode.column("student", "grade"), integer("90")))), row));
    }

    @Test
    void matchesRejectsNonBooleanConditionResults() {
        assertThrows(UnexpectedTypeException.class, () -> ExpressionEvaluator.matches(
                List.of(new ExpressionTree(integer("1")))));
        assertThrows(UnexpectedTypeException.class, () -> ExpressionEvaluator.matches(
                List.of(new ExpressionTree(real("1.0")))));
        assertThrows(UnexpectedTypeException.class, () -> ExpressionEvaluator.matches(
                List.of(new ExpressionTree(literal(ExpressionNode.Type.STRING_LITERAL, "true")))));
    }

    @Test
    void combinesRowsForJoinLikeColumnReferences() {
        IResultRow left = row("left_table", "id", 10L);
        IResultRow right = row("right_table", "id", 20L);

        assertEquals(10L, evaluate(ExpressionNode.column("left_table", "id"), left, right));
        assertEquals(20L, evaluate(ExpressionNode.column("right_table", "id"), left, right));
        assertEquals(30L, evaluate(binary("+",
                ExpressionNode.column("left_table", "id"),
                ExpressionNode.column("right_table", "id")), left, right));
    }

    @Test
    void rejectsUnknownAndIncomparableValues() {
        assertThrows(RuntimeException.class, () -> evaluate(comparison("=",
                literal(ExpressionNode.Type.STRING_LITERAL, "Ada"), integer("1"))));
        assertThrows(RuntimeException.class, () -> evaluate(binary("/",
                literal(ExpressionNode.Type.STRING_LITERAL, "A"),
                integer("2"))));
    }

    private static Object evaluate(ExpressionNode node, IResultRow... rows) {
        return ExpressionEvaluator.evaluate(new ExpressionTree(node), rows);
    }

    private static ExpressionNode literal(ExpressionNode.Type type, String value) {
        return ExpressionNode.literal(type, value);
    }

    private static ExpressionNode integer(String value) {
        return literal(ExpressionNode.Type.INTEGER_LITERAL, value);
    }

    private static ExpressionNode real(String value) {
        return literal(ExpressionNode.Type.REAL_LITERAL, value);
    }

    private static ExpressionNode binary(String operator, ExpressionNode left, ExpressionNode right) {
        return ExpressionNode.binary(operator, left, right);
    }

    private static ExpressionTree comparison(String column, String operator, ExpressionNode value) {
        return new ExpressionTree(ExpressionNode.comparison(operator,
                ExpressionNode.column("student", column), value));
    }

    private static ExpressionNode comparison(String operator, ExpressionNode left, ExpressionNode right) {
        return ExpressionNode.comparison(operator, left, right);
    }

    private static IResultRow row(String tableName, String columnName, Object value) {
        MockOperator source = new MockOperator();
        source.addColumn(new ColumnReference(tableName, columnName));
        source.newRow(value);
        return source.getRow(0);
    }
}
