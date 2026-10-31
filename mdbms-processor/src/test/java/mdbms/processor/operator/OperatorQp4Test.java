package mdbms.processor.operator;

import mdbms.models.RowId;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultTableSchema;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OperatorQp4Test {
    private static IResultRow row(int nilai) {
        return new IResultRow() {
            @Override
            public ResultTableSchema getSchema() { return new ResultTableSchema(); }

            @Override
            public Object get(String columnName) { return "nilai".equals(columnName) ? nilai : null; }

            @Override
            public Object get(String tableName, String columnName) { return get(columnName); }

            @Override
            public RowId getRowId() { return null; }
        };
    }

    private static IQueryOperator source(int... values) {
        return new IQueryOperator() {
            private int index;

            @Override
            public void open() { index = 0; }

            @Override
            public IResultRow next() {
                return index < values.length ? row(values[index++]) : null;
            }

            @Override
            public void close() { index = 0; }
        };
    }

    private static List<Integer> collect(IQueryOperator operator) {
        List<Integer> result = new ArrayList<>();
        operator.open();
        try {
            IResultRow current;
            while ((current = operator.next()) != null) {
                result.add(((Number) current.get("nilai")).intValue());
            }
        } finally {
            operator.close();
        }
        return result;
    }

    @Test
    void sortAscending() {
        assertEquals(List.of(2, 5, 9), collect(new SortOperator(source(9, 2, 5), "nilai", true)));
    }

    @Test
    void sortDescending() {
        assertEquals(List.of(9, 5, 2), collect(new SortOperator(source(9, 2, 5), "nilai", false)));
    }

    @Test
    void limitReturnsRequestedRows() {
        assertEquals(List.of(9, 2), collect(new LimitOperator(source(9, 2, 5), 2)));
    }

    @Test
    void limitZeroReturnsNoRows() {
        assertEquals(List.of(), collect(new LimitOperator(source(9, 2), 0)));
    }

    @Test
    void sortThenLimit() {
        assertEquals(List.of(2, 5), collect(new LimitOperator(new SortOperator(source(9, 2, 5), "nilai", true), 2)));
    }

    @Test
    void negativeLimitRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LimitOperator(source(1), -1));
    }
}
