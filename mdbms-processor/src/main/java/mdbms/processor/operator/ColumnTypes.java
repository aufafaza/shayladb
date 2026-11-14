package mdbms.processor.operator;

import mdbms.models.schema.DataType;
import mdbms.processor.exception.QueryExecutionException;

// SM hanya menerima Integer untuk INTEGER dan Float untuk FLOAT, sedangkan evaluator menghasilkan Long/Double
final class ColumnTypes {
    private ColumnTypes() {
    }

    static Object toColumnType(Object value, DataType type) {
        if (value instanceof Number number) {
            if (type == DataType.INTEGER) {
                double d = number.doubleValue();
                if (!(d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE)) throw outOfRange(value, type);
                return number.intValue();
            }
            if (type == DataType.FLOAT) {
                float f = number.floatValue();
                if (!Float.isFinite(f)) throw outOfRange(value, type);
                return f;
            }
        }
        return value;
    }

    private static QueryExecutionException outOfRange(Object value, DataType type) {
        return new QueryExecutionException("Value " + value + " is out of range for " + type + " column.");
    }
}
