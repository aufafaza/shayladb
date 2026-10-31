package mdbms.models;

public record RowId(String tableName, int blockNumber, int slot){}