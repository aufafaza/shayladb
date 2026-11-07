package mdbms.models.schema;

import java.util.List;

public record TableSchema(String tableName, List<ColumnDefinition> columns, List<String> primaryKey, List<ForeignKeyConstraint> foreignKeys){}