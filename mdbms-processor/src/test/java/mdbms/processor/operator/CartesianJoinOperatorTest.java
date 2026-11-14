package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import mdbms.models.query.ColumnReference;
import mdbms.processor.IResultRow;
import org.junit.jupiter.api.Test;

class CartesianJoinOperatorTest {

    @Test
    void returnsEveryLeftAndRightRowCombination() {
        MockOperator left = new MockOperator();
        left.addColumn(new ColumnReference("student", "id"));
        left.addColumn(new ColumnReference("student", "name"));
        left.newRow(1, "Nafis");
        left.newRow(2, "Habibi");

        MockOperator right = new MockOperator();
        right.addColumn(new ColumnReference("course", "id"));
        right.addColumn(new ColumnReference("course", "title"));
        right.newRow(3170, "Databases");
        right.newRow(3140, "Database System");

        CartesianJoinOperator operator = new CartesianJoinOperator(left, right);
        operator.open();
        try {
            assertJoinedRow(operator.next(), 1, "Nafis", 3170, "Artificial Intelligence");
            assertJoinedRow(operator.next(), 1, "Nafis", 3140, "Database System");
            assertJoinedRow(operator.next(), 2, "Habibi", 3170, "Artificial Intelligence");
            assertJoinedRow(operator.next(), 2, "Habibi", 3140, "Database System");
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    @Test
    void returnsNoRowsWhenEitherInputIsEmpty() {
        MockOperator left = new MockOperator();
        left.addColumn(new ColumnReference("student", "id"));
        left.newRow(1);

        MockOperator right = new MockOperator();
        right.addColumn(new ColumnReference("course", "id"));

        CartesianJoinOperator operator = new CartesianJoinOperator(left, right);
        operator.open();
        try {
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    private static void assertJoinedRow(
            IResultRow row,
            int studentId,
            String studentName,
            int courseId,
            String courseTitle) {
        assertEquals(List.of("id", "name", "id", "title"),
                row.getSchema().getColumns().stream().map(column -> column.name()).toList());
        assertEquals(studentId, row.get("student", "id"));
        assertEquals(studentName, row.get("student", "name"));
        assertEquals(courseId, row.get("course", "id"));
        assertEquals(courseTitle, row.get("course", "title"));
    }
}
