package mdbms.processor.mocks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import mdbms.models.access.DataRetrieval;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;

import org.junit.jupiter.api.Test;

class MockDataAccessTest {
    @Test
    void projectsAndFiltersStringAndFloatColumns() {
        MockDataAccess dataAccess = new MockDataAccess();

        var rows = dataAccess.readBlock(new DataRetrieval(
                MockDataAccess.TABLE_NAME,
                List.of(MockDataAccess.NAME_COLUMN, MockDataAccess.SCORE_COLUMN),
                List.of(
                        new Condition(MockDataAccess.NAME_COLUMN, ComparisonOperator.EQ, "row-3"),
                        new Condition(MockDataAccess.SCORE_COLUMN, ComparisonOperator.GTE, 3.5d))))
                .rows();

        assertEquals(List.of(List.of("row-3", 3.5f)), rows.stream().map(row -> row.values()).toList());
    }
}
