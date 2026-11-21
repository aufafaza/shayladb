package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import mdbms.models.access.DataRetrieval;
import mdbms.models.query.ColumnReference;
import mdbms.models.data.RowId;
import mdbms.processor.ResultRow;
import mdbms.processor.mocks.MockConcurrencyControl;
import mdbms.processor.mocks.MockDataAccess;
import mdbms.processor.mocks.MockOperator;

import org.junit.jupiter.api.Test;

class DeleteOperatorTest {
    @Test
    void deletesRowsProducedByChildOperator() {
        MockDataAccess dataAccess = new MockDataAccess();
        MockConcurrencyControl concurrencyControl = new MockConcurrencyControl();
        MockOperator childOperator = new MockOperator();
        childOperator.addColumn(new ColumnReference(MockDataAccess.TABLE_NAME, MockDataAccess.ID_COLUMN));
        childOperator.addColumn(new ColumnReference(MockDataAccess.TABLE_NAME, MockDataAccess.SECOND_COLUMN));
        addTargetRow(childOperator, 1, 1);
        addTargetRow(childOperator, 2, 3);
        addTargetRow(childOperator, 3, 5);
        addTargetRow(childOperator, 5, 1);

        DeleteOperator operator = new DeleteOperator(
                childOperator,
                dataAccess,
                concurrencyControl,
                1,
                MockDataAccess.TABLE_NAME);

        operator.open();
        try {
            assertNull(operator.next());
            assertEquals(4, operator.getAffectedRows());

            List<Integer> remainingIds = dataAccess.readBlock(new DataRetrieval(
                    MockDataAccess.TABLE_NAME,
                    List.of(MockDataAccess.ID_COLUMN, MockDataAccess.SECOND_COLUMN),
                    List.of())).rows().stream()
                    .map(row -> (Integer) row.values().get(0))
                    .toList();
            assertEquals(List.of(4, 6, 7, 8), remainingIds);
            assertNull(operator.next());
        } finally {
            operator.close();
        }
    }

    private static void addTargetRow(MockOperator operator, int id, int otherId) {
        ResultRow row = (ResultRow) operator.newRow(id, otherId);
        row.setRowId(new RowId(MockDataAccess.TABLE_NAME, id - 1, 0));
    }
}
