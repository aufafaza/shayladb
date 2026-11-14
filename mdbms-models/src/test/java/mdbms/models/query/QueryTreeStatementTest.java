package mdbms.models.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class QueryTreeStatementTest {
    @Test
    void updateAssignmentKeepsColumnAndExpressionSeparateFromConditions() {
        QueryTree update = node(QueryNodeType.UPDATE);
        ColumnReference column = new ColumnReference(null, "salary");
        ExpressionTree value = value("1");

        update.setUpdateAssignment(column, value);

        assertSame(column, update.getUpdateColumn());
        assertSame(value, update.getUpdateExpression());
        assertTrue(update.getConditions().isEmpty());
    }

    @Test
    void invalidUpdateAssignmentDoesNotPartiallyChangePayload() {
        QueryTree update = node(QueryNodeType.UPDATE);
        ColumnReference column = new ColumnReference(null, "salary");
        ExpressionTree original = value("1");
        update.setUpdateAssignment(column, original);

        assertThrows(NullPointerException.class, () -> update.setUpdateAssignment(null, value("2")));
        assertThrows(NullPointerException.class,
            () -> update.setUpdateAssignment(new ColumnReference(null, "other"), null));
        assertThrows(IllegalArgumentException.class,
            () -> update.setUpdateAssignment(new ColumnReference(null, "*"), value("2")));
        assertSame(column, update.getUpdateColumn());
        assertSame(original, update.getUpdateExpression());
    }

    @Test
    void insertCopiesValuesAndExposesAReadOnlyOrderedList() {
        QueryTree insert = node(QueryNodeType.INSERT);
        ExpressionTree first = value("1");
        ExpressionTree second = value("2");
        List<ExpressionTree> supplied = new ArrayList<>(List.of(first, second));

        insert.setInsertValues(supplied);
        supplied.clear();

        assertEquals(List.of(first, second), insert.getInsertValues());
        assertThrows(UnsupportedOperationException.class, () -> insert.getInsertValues().clear());
        assertThrows(NullPointerException.class, () -> insert.setInsertValues(null));
        assertThrows(NullPointerException.class, () -> insert.setInsertValues(Arrays.asList(first, null)));
        assertThrows(IllegalArgumentException.class, () -> insert.setInsertValues(List.of()));
        assertEquals(List.of(first, second), insert.getInsertValues());
    }

    @Test
    void indexDefinitionKeepsNameAndColumnAndRejectsPartialUpdates() {
        QueryTree index = node(QueryNodeType.CREATE_INDEX);
        ColumnReference column = new ColumnReference(null, "id");

        index.setIndexDefinition("idx_id", column);

        assertEquals("idx_id", index.getIndexName());
        assertSame(column, index.getIndexColumn());
        assertThrows(NullPointerException.class, () -> index.setIndexDefinition(null, column));
        assertThrows(IllegalArgumentException.class, () -> index.setIndexDefinition(" ", column));
        assertThrows(NullPointerException.class, () -> index.setIndexDefinition("other", null));
        assertThrows(IllegalArgumentException.class,
            () -> index.setIndexDefinition("other", new ColumnReference(null, "*")));
        assertEquals("idx_id", index.getIndexName());
        assertSame(column, index.getIndexColumn());
    }

    @Test
    void statementSettersRejectEveryOtherNodeKind() {
        for (QueryNodeType type : QueryNodeType.values()) {
            QueryTree tree = node(type);
            ColumnReference column = new ColumnReference(null, "id");
            if (type != QueryNodeType.UPDATE) {
                assertThrows(IllegalStateException.class, () -> tree.setUpdateAssignment(column, value("1")));
            }
            if (type != QueryNodeType.INSERT) {
                assertThrows(IllegalStateException.class, () -> tree.setInsertValues(List.of(value("1"))));
            }
            if (type != QueryNodeType.CREATE_INDEX) {
                assertThrows(IllegalStateException.class, () -> tree.setIndexDefinition("idx", column));
            }
        }
    }

    @Test
    void existingConstructorsLeaveStatementPayloadsAbsent() {
        QueryTree basic = node(QueryNodeType.TABLE_SCAN);
        QueryTree extended = new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(basic), 0, List.of(new ColumnReference(null, "id")), false);

        for (QueryTree tree : List.of(basic, extended)) {
            assertNull(tree.getUpdateColumn());
            assertNull(tree.getUpdateExpression());
            assertTrue(tree.getInsertValues().isEmpty());
            assertNull(tree.getIndexName());
            assertNull(tree.getIndexColumn());
        }
    }

    private static QueryTree node(QueryNodeType type) {
        return new QueryTree(type, PhysicalAlgorithm.DEFAULT, "a", List.of(), List.of(), 0);
    }

    private static ExpressionTree value(String text) {
        return new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, text));
    }
}
