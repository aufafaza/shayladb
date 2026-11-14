package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.processor.IResultRow;
import org.junit.jupiter.api.Test;

final class JoinOperatorTest {
    @Test
    void joinsEveryPairWhenNoConditionIsProvided() {
        MockOperator left = new MockOperator();
        left.addColumn(new ColumnReference("student", "id"));
        left.addColumn(new ColumnReference("student", "name"));
        left.newRow(1, "Nafis");
        left.newRow(2, "Habibi");

        MockOperator right = new MockOperator();
        right.addColumn(new ColumnReference("course", "id"));
        right.addColumn(new ColumnReference("course", "title"));
        right.newRow(3170, "Artificial Intelligence");
        right.newRow(3140, "Database System");

        JoinOperator operator = new JoinOperator(left, right, List.of(), false, 1);
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
    void naturalJoinMatchesCommonColumnsAndOutputsThemOnlyOnce() {
        MockOperator left = new MockOperator();
        left.addColumn(new ColumnReference("student", "id"));
        left.addColumn(new ColumnReference("student", "name"));
        left.newRow(1, "Nafis");
        left.newRow(2, "Habibi");

        MockOperator right = new MockOperator();
        right.addColumn(new ColumnReference("registration", "id"));
        right.addColumn(new ColumnReference("registration", "course"));
        right.newRow(1, "Artificial Intelligence");
        right.newRow(3, "Database System");

        JoinOperator operator = new JoinOperator(left, right, List.of(), true, 2);
        operator.open();
        try {
            IResultRow result = operator.next();

            assertEquals(List.of("id", "name", "course"),
                    result.getSchema().getColumns().stream().map(column -> column.name()).toList());
            assertEquals("student", result.getSchema().getColumnTableOrigin(0));
            assertEquals(1, result.get("student", "id"));
            assertEquals("Nafis", result.get("student", "name"));
            assertEquals("Artificial Intelligence", result.get("registration", "course"));
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    @Test
    void joinsRowsThatMatchTheOnCondition() {
        MockOperator left = new MockOperator();
        left.addColumn(new ColumnReference("student", "id"));
        left.addColumn(new ColumnReference("student", "name"));
        left.newRow(1, "Nafis");
        left.newRow(2, "Habibi");

        MockOperator right = new MockOperator();
        right.addColumn(new ColumnReference("registration", "student_id"));
        right.addColumn(new ColumnReference("registration", "course"));
        right.newRow(2, "Artificial Intelligence");
        right.newRow(1, "Database System");
        right.newRow(3, "Parallel and Distributed System");

        ExpressionTree onCondition = new ExpressionTree(ExpressionNode.comparison(
                "=",
                ExpressionNode.column("student", "id"),
                ExpressionNode.column("registration", "student_id")));
        JoinOperator operator = new JoinOperator(left, right, List.of(onCondition), false, 2);
        operator.open();
        try {
            IResultRow first = operator.next();
            assertEquals(1, first.get("student", "id"));
            assertEquals("Database System", first.get("registration", "course"));

            IResultRow second = operator.next();
            assertEquals(2, second.get("student", "id"));
            assertEquals("Artificial Intelligence", second.get("registration", "course"));
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
        assertEquals(studentId, row.get("student", "id"));
        assertEquals(studentName, row.get("student", "name"));
        assertEquals(courseId, row.get("course", "id"));
        assertEquals(courseTitle, row.get("course", "title"));
    }
}
