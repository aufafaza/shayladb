package mdbms.optimizer.binder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.DropBehavior;
import mdbms.models.schema.ForeignKeyAction;
import mdbms.models.schema.ForeignKeyConstraint;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.exception.QuerySemanticException;

/** Catalog checks for DDL; binding never mutates the catalog. */
final class DdlValidator {
    private final ISchemaCatalog catalog;

    DdlValidator(ISchemaCatalog catalog) {
        this.catalog = catalog;
    }

    TableSchema findTable(String name) {
        List<String> tables = catalog.listTable();
        if (tables != null) {
            return tables.stream().filter(table -> table.equalsIgnoreCase(name)).findFirst()
                .map(catalog::getTableSchema).orElse(null);
        }
        return catalog.getTableSchema(name);
    }

    TableSchema validateCreate(QueryTree node) {
        TableSchema schema = node.getTableDefinition();
        if (schema == null || schema.columns().isEmpty()) fail("CREATE TABLE requires column definitions");
        if (findTable(node.getTableName()) != null) fail("Table '" + node.getTableName() + "' already exists");
        Set<String> names = new HashSet<>();
        for (ColumnDefinition column : schema.columns()) {
            if (column.name() == null || column.name().isBlank() || !names.add(key(column.name()))) {
                fail("Duplicate or invalid column name '" + column.name() + "'");
            }
            if (column.type() == null || ((column.type() == DataType.CHAR || column.type() == DataType.VARCHAR)
                    && column.length() <= 0)) fail("Invalid type or length for column '" + column.name() + "'");
        }
        List<String> primaryKey = new ArrayList<>();
        for (String name : schema.primaryKey()) {
            ColumnDefinition column = column(schema, name);
            if (primaryKey.contains(column.name())) fail("Duplicate PRIMARY KEY column '" + name + "'");
            primaryKey.add(column.name());
        }
        List<ForeignKeyConstraint> foreignKeys = new ArrayList<>();
        for (ForeignKeyConstraint foreignKey : schema.foreignKeys()) {
            ColumnDefinition source = column(schema, foreignKey.column());
            TableSchema target = schema.tableName().equalsIgnoreCase(foreignKey.referencedTable())
                ? schema : findTable(foreignKey.referencedTable());
            if (target == null) fail("Referenced table '" + foreignKey.referencedTable() + "' does not exist");
            ColumnDefinition referenced = column(target, foreignKey.referencedColumn());
            if (target.primaryKey().size() != 1
                    || !target.primaryKey().get(0).equalsIgnoreCase(referenced.name())) {
                fail("FOREIGN KEY must reference a single-column PRIMARY KEY");
            }
            if (source.type() != referenced.type()
                    || ((source.type() == DataType.CHAR || source.type() == DataType.VARCHAR)
                        && source.length() != referenced.length())) {
                fail("FOREIGN KEY type mismatch for column '" + source.name() + "'");
            }
            if (foreignKey.onDelete() == null || foreignKey.onUpdate() == null) fail("Missing FOREIGN KEY action");
            if ((!source.nullable() || primaryKey.contains(source.name()))
                    && (foreignKey.onDelete() == ForeignKeyAction.SET_NULL
                        || foreignKey.onUpdate() == ForeignKeyAction.SET_NULL)) {
                fail("SET NULL requires a nullable FOREIGN KEY column '" + source.name() + "'");
            }
            foreignKeys.add(new ForeignKeyConstraint(source.name(), target.tableName(), referenced.name(),
                foreignKey.onDelete(), foreignKey.onUpdate()));
        }
        List<ColumnDefinition> columns = schema.columns().stream().map(column -> new ColumnDefinition(
            column.name(), column.type(), column.length(), column.nullable() && !primaryKey.contains(column.name()))).toList();
        return new TableSchema(schema.tableName(), columns, primaryKey, foreignKeys);
    }

    void validateDrop(QueryTree node) {
        if (node.getDropBehavior() == DropBehavior.CASCADE) return;
        for (String name : catalog.listTable()) {
            if (name.equalsIgnoreCase(node.getTableName())) continue;
            if (catalog.getTableSchema(name).foreignKeys().stream()
                    .anyMatch(key -> key.referencedTable().equalsIgnoreCase(node.getTableName()))) {
                fail("Cannot DROP TABLE '" + node.getTableName() + "': referenced by '" + name + "' (RESTRICT)");
            }
        }
    }

    private ColumnDefinition column(TableSchema schema, String name) {
        return schema.columns().stream().filter(column -> column.name().equalsIgnoreCase(name)).findFirst()
            .orElseThrow(() -> new QuerySemanticException("Column '" + name + "' does not exist in table '"
                + schema.tableName() + "'", name));
    }

    private static String key(String value) { return value.toLowerCase(Locale.ROOT); }
    private static void fail(String message) { throw new QuerySemanticException(message); }
}
