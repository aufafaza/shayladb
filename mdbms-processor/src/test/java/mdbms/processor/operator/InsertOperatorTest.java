package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import mdbms.models.data.RowId;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.processor.mocks.MockDataAccess;
import mdbms.processor.mocks.MockSchemaCatalog;
import mdbms.processor.mocks.MockConcurrencyControl;
import org.junit.jupiter.api.Test;

class InsertOperatorTest {
    @Test
    void insertsRowAndAddsItToIndex() {
        MockDataAccess dataAccess = new MockDataAccess();
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        catalog.updateTableSchema(new TableSchema(
                MockDataAccess.TABLE_NAME,
                List.of(
                        new ColumnDefinition(MockDataAccess.ID_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.SECOND_COLUMN, DataType.INTEGER, 0, false)),
                List.of(),
                List.of()));
        InsertOperator operator = new InsertOperator(
                dataAccess,
                catalog,
                new MockConcurrencyControl(),
                1,
                MockDataAccess.TABLE_NAME,
                List.of(
                        integerLiteral("9"),
                        integerLiteral("17")));

        operator.open();
        try {
            assertNull(operator.next());
            assertEquals(1, operator.getAffectedRows());

            RowId insertedRowId = new RowId(MockDataAccess.TABLE_NAME, MockDataAccess.TABLE_SIZE, 0);
            assertEquals(List.of(9, 17), dataAccess.fetchByRowId(insertedRowId).values());
            assertEquals(List.of(insertedRowId), dataAccess.searchIndex(MockDataAccess.INDEX_NAME, 17));
        } finally {
            operator.close();
        }
    }

    private static ExpressionTree integerLiteral(String value) {
        return new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, value));
    }
}
