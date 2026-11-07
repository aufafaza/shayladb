package mdbms.models.schema;

public record ColumnDefinition(String name, DataType type, int length, boolean nullable){}