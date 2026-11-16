package mdbms.processor.utility;

import java.util.ArrayList;
import java.util.List;

import mdbms.models.data.RowId;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultTableSchema;
import mdbms.processor.operator.IQueryOperator;

public class IntegerRowUtility {
    private static IResultRow row(int value) {
        return new IResultRow() {
            @Override
            public ResultTableSchema getSchema() {
                return new ResultTableSchema();
            }

            @Override
            public Object get(String columnName) {
                if ("value".equals(columnName))
                    return value;
                if ("group".equals(columnName))
                    return value % 3;
                return null;
            }

            @Override
            public Object get(String tableName, String columnName) {
                return get(columnName);
            }

            @Override
            public RowId getRowId() {
                return null;
            }
        };
    }

    public static List<Integer> collect(IQueryOperator operator) {
        List<Integer> result = new ArrayList<>();
        operator.open();
        try {
            IResultRow current;
            while ((current = operator.next()) != null) {
                result.add(((Number) current.get("value")).intValue());
            }
        } finally {
            operator.close();
        }
        return result;
    }

    public static IQueryOperator source(int... values) {
        return new IQueryOperator() {
            private int index;

            @Override
            public void open() {
                index = 0;
            }

            @Override
            public IResultRow next() {
                return index < values.length ? row(values[index++]) : null;
            }

            @Override
            public void close() {
                index = 0;
            }
        };
    }

}
