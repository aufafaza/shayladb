package mdbms.models;

import java.util.List;

public record(String tableName, List<ColumnDefinition> columns, List<String> primaryKey, List<ForeignKeyConstraint> foreignKeys){}