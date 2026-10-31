package mdbms.processor;

import mdbms.models.RowId;

public interface IResultRow {
    public ResultTableSchema getSchema();

    public Object get(String columnName);

    public Object get(String tableName, String columnName);

    public RowId getRowId();
}
