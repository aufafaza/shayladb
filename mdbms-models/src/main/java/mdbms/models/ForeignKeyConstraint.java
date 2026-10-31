package mdbms.models;

public record ForeignKeyConstraint(String column, String referencedTable, String referencedColumn, ForeignKeyAction onDelete, ForeignKeyAction onUpdate){}