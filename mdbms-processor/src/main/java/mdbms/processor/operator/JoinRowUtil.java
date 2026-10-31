package mdbms.processor.operator;

import mdbms.models.ColumnDefinition;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

final class JoinRowUtil {
    private JoinRowUtil() {}
    static boolean hasName(ResultTableSchema schema, String name) {
        for (ColumnDefinition col : schema.getColumns()) if (col.name().equals(name)) return true;
        return false;
    }
    static ResultRow combine(IResultRow left, IResultRow right, boolean natural) {
        ResultTableSchema schema = new ResultTableSchema();
        for (ColumnDefinition c : left.getSchema().getColumns()) {
            schema.addColumn(left.getSchema().getColumnTableOrigin(c), c);
        }
        for (ColumnDefinition c : right.getSchema().getColumns()) {
            if (!natural || !hasName(left.getSchema(), c.name())) {
                schema.addColumn(right.getSchema().getColumnTableOrigin(c), c);
            }
        }
        ResultRow result = new ResultRow(schema);
        for (ColumnDefinition c : left.getSchema().getColumns()) {
            String table = left.getSchema().getColumnTableOrigin(c);
            result.set(table, c.name(), left.get(table, c.name()));
        }
        for (ColumnDefinition c : right.getSchema().getColumns()) {
            if (natural && hasName(left.getSchema(), c.name())) continue;
            String table = right.getSchema().getColumnTableOrigin(c);
            result.set(table, c.name(), right.get(table, c.name()));
        }
        return result;
    }
    static boolean naturalMatch(IResultRow left, IResultRow right) {
        for (ColumnDefinition a : left.getSchema().getColumns()) {
            for (ColumnDefinition b : right.getSchema().getColumns()) {
                if (!a.name().equals(b.name())) continue;
                Object x = left.get(left.getSchema().getColumnTableOrigin(a), a.name());
                Object y = right.get(right.getSchema().getColumnTableOrigin(b), b.name());
                if (x == null || y == null || !x.equals(y)) return false;
            }
        }
        return true;
    }
}
