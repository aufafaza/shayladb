package mdbms.models.data;

public record RowId(String tableName, int blockNumber, int slot){}