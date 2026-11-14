package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import mdbms.models.query.ColumnReference;
import mdbms.processor.IResultRow;
import mdbms.processor.exception.AmbiguousColumnException;
import org.junit.jupiter.api.Test;

class ProjectOperatorTest {

    @Test
    void projectsSelectedColumnsInRequestedOrder() {
        MockOperator input = new MockOperator();
        input.addColumn(new ColumnReference("users", "id"));
        input.addColumn(new ColumnReference("users", "name"));
        input.addColumn(new ColumnReference("users", "email"));
        input.newRow(7, "Nafis", "nafis@example.com");
        input.newRow(8, "Habibi", "Habibi@example.com");

        ProjectOperator operator = new ProjectOperator(input,
                List.of(new ColumnReference("users", "id"),
                        new ColumnReference("users", "name")));
        operator.open();
        try {
            IResultRow first = operator.next();
            assertEquals(List.of("id", "name"),
                    first.getSchema().getColumns().stream().map(column -> column.name()).toList());
            assertEquals("users", first.getSchema().getColumnTableOrigin(0));
            assertEquals("Nafis", first.get("users", "name"));
            assertEquals(7, first.get("users", "id"));

            IResultRow second = operator.next();
            assertEquals("Habibi", second.get("users", "name"));
            assertEquals(8, second.get("users", "id"));
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    @Test
    void resolvesUnqualifiedColumnUsingInputOrigin() {
        MockOperator input = new MockOperator();
        input.addColumn(new ColumnReference("users", "id"));
        input.newRow(7);

        ProjectOperator operator = new ProjectOperator(input, List.of(new ColumnReference(null, "id")));
        operator.open();
        try {
            IResultRow result = operator.next();

            assertEquals("users", result.getSchema().getColumnTableOrigin(0));
            assertEquals(7, result.get("users", "id"));
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    @Test
    void rejectsUnknownColumn() {
        MockOperator input = new MockOperator();
        input.addColumn(new ColumnReference("users", "id"));
        input.newRow(7);

        ProjectOperator operator = new ProjectOperator(input, List.of(new ColumnReference("users", "missing")));
        operator.open();
        try {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, operator::next);
            assertEquals("Unknown column: users.missing", exception.getMessage());
        } finally {
            operator.close();
        }
    }

    @Test
    void rejectsAmbiguousUnqualifiedColumn() {
        MockOperator input = new MockOperator();
        input.addColumn(new ColumnReference("student", "id"));
        input.addColumn(new ColumnReference("teacher", "id"));
        input.newRow(7, 42);

        ProjectOperator operator = new ProjectOperator(input, List.of(new ColumnReference(null, "id")));
        operator.open();
        try {
            AmbiguousColumnException exception = assertThrows(AmbiguousColumnException.class, operator::next);
            assertEquals("Failed to execute query. Ambiguous column name 'id'.", exception.getMessage());
        } finally {
            operator.close();
        }
    }
}
