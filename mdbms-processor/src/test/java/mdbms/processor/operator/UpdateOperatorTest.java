package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import mdbms.models.data.RowId;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.processor.ResultRow;
import mdbms.processor.mocks.MockConcurrencyControl;
import mdbms.processor.mocks.MockDataAccess;
import mdbms.processor.mocks.MockOperator;
import mdbms.processor.mocks.MockSchemaCatalog;

import org.junit.jupiter.api.Test;

class UpdateOperatorTest {
    @Test
    void updatesSelectedRowsAndMaintainsTheIndex() {
        MockDataAccess dataAccess = new MockDataAccess();
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        catalog.updateTableSchema(new TableSchema(
                MockDataAccess.TABLE_NAME,
                List.of(
                        new ColumnDefinition(MockDataAccess.ID_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.SECOND_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.NAME_COLUMN, DataType.VARCHAR, 32, false),
                        new ColumnDefinition(MockDataAccess.SCORE_COLUMN, DataType.FLOAT, 0, false)),
                List.of(),
                List.of()));

        MockOperator childOperator = new MockOperator();
        childOperator.addColumn(new ColumnReference(MockDataAccess.TABLE_NAME, MockDataAccess.ID_COLUMN));
        childOperator.addColumn(new ColumnReference(MockDataAccess.TABLE_NAME, MockDataAccess.SECOND_COLUMN));
        childOperator.addColumn(new ColumnReference(MockDataAccess.TABLE_NAME, MockDataAccess.NAME_COLUMN));
        childOperator.addColumn(new ColumnReference(MockDataAccess.TABLE_NAME, MockDataAccess.SCORE_COLUMN));
        addTargetRow(childOperator, 2, 3, "row-2", 2.5f);
        addTargetRow(childOperator, 3, 5, "row-3", 3.5f);

        UpdateOperator operator = new UpdateOperator(
                childOperator,
                dataAccess,
                catalog,
                new MockConcurrencyControl(),
                1,
                MockDataAccess.TABLE_NAME,
                MockDataAccess.SECOND_COLUMN,
                new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "9")));

        operator.open();
        try {
            assertNull(operator.next());
            assertEquals(2, operator.getAffectedRows());

            RowId firstRowId = new RowId(MockDataAccess.TABLE_NAME, 1, 0);
            RowId secondRowId = new RowId(MockDataAccess.TABLE_NAME, 2, 0);
            assertEquals(List.of(2, 9, "row-2", 2.5f), dataAccess.fetchByRowId(firstRowId).values());
            assertEquals(List.of(3, 9, "row-3", 3.5f), dataAccess.fetchByRowId(secondRowId).values());
            assertEquals(List.of(firstRowId, secondRowId),
                    dataAccess.searchIndex(MockDataAccess.INDEX_NAME, 9));
            assertEquals(List.of(new RowId(MockDataAccess.TABLE_NAME, 5, 0)),
                    dataAccess.searchIndex(MockDataAccess.INDEX_NAME, 3));
            assertEquals(List.of(new RowId(MockDataAccess.TABLE_NAME, 6, 0)),
                    dataAccess.searchIndex(MockDataAccess.INDEX_NAME, 5));
        } finally {
            operator.close();
        }
    }

    private static void addTargetRow(MockOperator operator, int id, int otherId, String name, float score) {
        ResultRow row = (ResultRow) operator.newRow(id, otherId, name, score);
        row.setRowId(new RowId(MockDataAccess.TABLE_NAME, id - 1, 0));
    }
}
