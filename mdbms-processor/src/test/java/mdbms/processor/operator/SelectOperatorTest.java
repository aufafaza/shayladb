package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.processor.IResultRow;
import org.junit.jupiter.api.Test;

class SelectOperatorTest {

    @Test
    void returnsOnlyRowsMatchingAllConditions() {
        MockOperator input = new MockOperator();
        input.addColumn(new ColumnReference("student", "id"));
        input.addColumn(new ColumnReference("student", "grade"));
        input.addColumn(new ColumnReference("student", "status"));
        input.newRow(1, 95, "failed");
        input.newRow(2, 80, "pass");
        input.newRow(3, 70, "pass");
        input.newRow(4, 90, "pass");

        SelectOperator operator = new SelectOperator(input, List.of(
                comparison("grade", ">=", ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "80")),
                comparison("status", "=", ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "pass"))));

        operator.open();
        try {
            IResultRow first = operator.next();
            assertEquals(2, first.get("student", "id"));

            IResultRow second = operator.next();
            assertEquals(4, second.get("student", "id"));
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    @Test
    void returnsAllRowsWhenThereAreNoConditions() {
        MockOperator input = new MockOperator();
        input.addColumn(new ColumnReference("student", "id"));
        input.newRow(1);
        input.newRow(2);

        SelectOperator operator = new SelectOperator(input, List.of());
        operator.open();
        try {
            assertEquals(1, operator.next().get("student", "id"));
            assertEquals(2, operator.next().get("student", "id"));
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    private static ExpressionTree comparison(String column, String operator, ExpressionNode value) {
        return new ExpressionTree(ExpressionNode.comparison(
                operator,
                ExpressionNode.column("student", column),
                value));
    }
}
