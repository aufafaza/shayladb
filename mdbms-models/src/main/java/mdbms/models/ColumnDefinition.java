package mdbms.models;

public record ColumnDefinition(String name, DataType type, int length, boolean nullable){}