package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.query.IndexScanSpec;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.processor.IResultRow;
import org.junit.jupiter.api.Test;

class IndexScanOperatorTest {
    @Test
    void equalityScanReturnsRowsForDuplicateIndexKeys() {
        IndexScanOperator operator = createOperator(ComparisonOperator.EQ, 3);

        operator.open();
        try {
            List<Integer> ids = new ArrayList<>();
            IResultRow row;
            while ((row = operator.next()) != null) {
                ids.add((Integer) row.get(MockDataAccess.ID_COLUMN));
                assertEquals(3, row.get(MockDataAccess.SECOND_COLUMN));
            }

            assertEquals(List.of(2, 6), ids);
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    @Test
    void inclusiveRangeScanReturnsRowsAtAndAboveTheLowerBound() {
        IndexScanOperator operator = createOperator(ComparisonOperator.GTE, 5);

        operator.open();
        try {
            List<Integer> ids = new ArrayList<>();
            IResultRow row;
            while ((row = operator.next()) != null) {
                ids.add((Integer) row.get(MockDataAccess.ID_COLUMN));
            }

            assertEquals(List.of(3, 7, 4, 8), ids);
        } finally {
            operator.close();
        }
    }

    private static IndexScanOperator createOperator(ComparisonOperator operator, int value) {
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
        var index = catalog.createIndex(
                MockDataAccess.INDEX_NAME,
                MockDataAccess.TABLE_NAME,
                MockDataAccess.SECOND_COLUMN);
        return new IndexScanOperator(
                dataAccess,
                catalog,
                concurrencyControl,
                1,
                MockDataAccess.TABLE_NAME,
                new IndexScanSpec(index, new Condition(MockDataAccess.SECOND_COLUMN, operator, value)));
    }
}
