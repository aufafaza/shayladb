package mdbms.models.schema;

public record ForeignKeyConstraint(String column, String referencedTable, String referencedColumn, ForeignKeyAction onDelete, ForeignKeyAction onUpdate){}