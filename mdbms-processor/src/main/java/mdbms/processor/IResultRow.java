package mdbms.processor;

public interface IResultRow {
    public ResultTableSchema getSchema();

    public Object get(String columnName);

    public Object get(String tableName, String columnName);
}
