package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.processor.IResultRow;
import org.junit.jupiter.api.Test;

class SequentialScanOperatorTest {
    @Test
    void scansEveryRowInTableOrder() {
        SequentialScanOperator operator = createOperator();

        operator.open();
        try {
            List<Integer> ids = new ArrayList<>();
            List<Integer> otherIds = new ArrayList<>();
            IResultRow row;
            while ((row = operator.next()) != null) {
                ids.add((Integer) row.get(MockDataAccess.ID_COLUMN));
                otherIds.add((Integer) row.get(MockDataAccess.SECOND_COLUMN));
                assertEquals(MockDataAccess.TABLE_NAME, row.getRowId().tableName());
            }

            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), ids);
            assertEquals(List.of(1, 3, 5, 7, 1, 3, 5, 7), otherIds);
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    private static SequentialScanOperator createOperator() {
        MockDataAccess dataAccess = new MockDataAccess();
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        MockConcurrencyControl concurrencyControl = new MockConcurrencyControl();
        catalog.updateTableSchema(new TableSchema(
                MockDataAccess.TABLE_NAME,
                List.of(
                        new ColumnDefinition(MockDataAccess.ID_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.SECOND_COLUMN, DataType.INTEGER, 0, false)),
                List.of(),
                List.of()));
        return new SequentialScanOperator(
                dataAccess,
                catalog,
                concurrencyControl,
                1,
                MockDataAccess.TABLE_NAME, "qualifier");
    }
}
