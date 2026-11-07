package mdbms.models.schema;

public record IndexInfo(String indexName, String tableName, String columnName, IndexType type){}