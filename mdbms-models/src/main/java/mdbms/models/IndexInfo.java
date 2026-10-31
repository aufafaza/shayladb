package mdbms.models;

public record IndexInfo(String indexName, String tableName, String columnName, IndexType type){}