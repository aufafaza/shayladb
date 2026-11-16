package mdbms.optimizer.binder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.OrderByClause;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QuerySemanticException;

public class QueryBinder {
    private final ISchemaCatalog schemaCatalog;
    private final QueryErrorCollector errorCollector;

    public QueryBinder(ISchemaCatalog schemaCatalog) {
        this(schemaCatalog, null);
    }

    public QueryBinder(ISchemaCatalog schemaCatalog, QueryErrorCollector errorCollector) {
        this.schemaCatalog = Objects.requireNonNull(schemaCatalog, "schemaCatalog must not be null");
        this.errorCollector = errorCollector;
    }

    private QuerySemanticException semanticError(String message) {
        return semanticError(message, null);
    }

    private QuerySemanticException semanticError(String message, String problematicName) {
        QuerySemanticException ex = new QuerySemanticException(message, problematicName);
        if (errorCollector != null) {
            errorCollector.addException(ex);
        }
        return ex;
    }

    public BindingContext bind(QueryTree query) {
        if (query == null) {
            return new BindingContext();
        }
        BindingContext context = new BindingContext();

        // Pass 1: Resolve all tables referenced in FROM, scans, or statements
        collectAndRegisterTables(query, context);

        // Pass 2: Resolve and bind all nodes (projections, conditions, joins, DML)
        bindNode(query, context);

        return context;
    }

    private void bindNode(QueryTree node, BindingContext context) {
        if (node == null) {
            return;
        }

        // Visit children first (post-order)
        for (QueryTree child : node.getChildren()) {
            bindNode(child, context);
        }

        switch (node.getNodeType()) {
            case JOIN -> {
                if (node.isNaturalJoin()) {
                    resolveNaturalJoin(node, context);
                }
                for (ExpressionTree cond : node.getConditions()) {
                    bindExpression(cond, context, node);
                }
            }
            case SELECT -> {
                for (ExpressionTree cond : node.getConditions()) {
                    bindExpression(cond, context, node);
                }
            }
            case SORT -> {
                for (OrderByClause clause : node.getOrderByClauses()) {
                    if (clause.getField() != null) {
                        ColumnBinding cb = resolveColumn(clause.getField(), context, node);
                        context.registerColumnBinding(clause.getField(), cb);
                    }
                }
            }
            case PROJECT -> {
                expandSelectStar(node, context);
                for (ColumnReference col : node.getProjectionColumns()) {
                    if (!col.isWildcard()) {
                        ColumnBinding cb = resolveColumn(col, context, node);
                        context.registerColumnBinding(col, cb);
                    }
                }
            }
            case UPDATE -> {
                if (node.getUpdateColumn() != null) {
                    ColumnBinding cb = resolveColumn(node.getUpdateColumn(), context);
                    context.registerColumnBinding(node.getUpdateColumn(), cb);
                }
                if (node.getUpdateExpression() != null) {
                    bindExpression(node.getUpdateExpression(), context, node);
                }
                for (ExpressionTree cond : node.getConditions()) {
                    bindExpression(cond, context, node);
                }
            }
            case INSERT -> {
                for (ExpressionTree val : node.getInsertValues()) {
                    bindExpression(val, context, node);
                }
            }
            case DELETE -> {
                for (ExpressionTree cond : node.getConditions()) {
                    bindExpression(cond, context, node);
                }
            }
            case CREATE_INDEX -> {
                if (node.getIndexColumn() != null) {
                    ColumnBinding cb = resolveColumn(node.getIndexColumn(), context);
                    context.registerColumnBinding(node.getIndexColumn(), cb);
                }
            }
            case LIMIT -> {
                if (node.getLimit() == null || node.getLimit() < 0) {
                    throw semanticError("LIMIT value must be non-negative");
                }
            }
            default -> {
                // Other nodes like CARTESIAN, TABLE_SCAN, BEGIN, COMMIT, ABORT require no direct column binding
            }
        }
    }

    private TableSchema resolveTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw semanticError("Table name cannot be null or blank");
        }
        TableSchema schema = schemaCatalog.getTableSchema(tableName);
        if (schema == null) {
            // Case-insensitive fallback lookup if listTable is supported
            List<String> tables = schemaCatalog.listTable();
            if (tables != null) {
                for (String t : tables) {
                    if (t.equalsIgnoreCase(tableName)) {
                        schema = schemaCatalog.getTableSchema(t);
                        break;
                    }
                }
            }
        }
        if (schema == null) {
            throw semanticError("Table '" + tableName + "' does not exist", tableName);
        }
        return schema;
    }

    private ColumnBinding resolveColumn(ColumnReference reference, BindingContext context) {
        if (reference == null) {
            throw semanticError("Column reference cannot be null");
        }
        String colName = reference.getColumnName();
        String tableName = reference.getTableName();

        if (tableName != null && !tableName.isBlank()) {
            // Qualified reference: table.col
            TableBinding tb = context.getTableBinding(tableName);
            if (tb == null) {
                throw semanticError("Table '" + tableName + "' is not referenced in the query", tableName);
            }
            TableSchema schema = tb.getSchema();
            ColumnDefinition matchedCol = null;
            for (ColumnDefinition col : schema.columns()) {
                if (col.name().equalsIgnoreCase(colName)) {
                    matchedCol = col;
                    break;
                }
            }
            if (matchedCol == null) {
                throw semanticError("Column '" + colName + "' does not exist in table '" + tableName + "'", colName);
            }
            ColumnBinding cb = new ColumnBinding(tb.getTableName(), tb.getAlias(), matchedCol);
            context.registerColumnBinding(reference, cb);
            return cb;
        } else {
            // Unqualified reference: col -> check all tables in FROM clause
            List<ColumnBinding> matches = new ArrayList<>();
            for (TableBinding tb : context.getTableBindings().values()) {
                TableSchema schema = tb.getSchema();
                for (ColumnDefinition col : schema.columns()) {
                    if (col.name().equalsIgnoreCase(colName)) {
                        matches.add(new ColumnBinding(tb.getTableName(), tb.getAlias(), col));
                        break;
                    }
                }
            }
            if (matches.isEmpty()) {
                throw semanticError("Column '" + colName + "' does not exist", colName);
            }
            // Check for ambiguity across distinct tables
            List<String> distinctTables = matches.stream()
                .map(ColumnBinding::getTableAlias)
                .distinct()
                .toList();
            if (distinctTables.size() > 1) {
                throw semanticError(
                    "Column '" + colName + "' is ambiguous (found in tables: " + distinctTables + ")",
                    colName
                );
            }
            ColumnBinding cb = matches.get(0);
            context.registerColumnBinding(reference, cb);
            return cb;
        }
    }

    private ColumnBinding resolveColumn(ColumnReference reference, BindingContext context, QueryTree node) {
        if (reference.getTableName() != null || node.getChildren().isEmpty()) {
            return resolveColumn(reference, context);
        }
        QueryTree source = node.getChildren().size() == 1 ? node.getChildren().get(0) : node;
        List<ColumnReference> matches = visibleColumns(source, context).stream()
            .filter(column -> column.getColumnName().equalsIgnoreCase(reference.getColumnName())).toList();
        if (matches.size() != 1) {
            throw semanticError("Column '" + reference.getColumnName() + "' is "
                + (matches.isEmpty() ? "not visible" : "ambiguous"), reference.getColumnName());
        }
        return resolveColumn(matches.get(0), context);
    }

    private void bindExpression(ExpressionTree expression, BindingContext context, QueryTree scope) {
        if (expression != null && expression.getRoot() != null) {
            bindExpressionNode(expression.getRoot(), context, scope);
        }
    }

    private void bindExpressionNode(ExpressionNode node, BindingContext context, QueryTree scope) {
        if (node == null) {
            return;
        }
        if (node.getType() == ExpressionNode.Type.COLUMN_REFERENCE) {
            ColumnReference ref = new ColumnReference(node.getTableName(), node.getValue());
            ColumnBinding cb = resolveColumn(ref, context, scope);
            context.registerColumnBinding(ref, cb);
        }
        bindExpressionNode(node.getLeft(), context, scope);
        bindExpressionNode(node.getRight(), context, scope);
    }

    private void expandSelectStar(QueryTree query, BindingContext context) {
        if (query.getProjectionColumns().stream().noneMatch(ColumnReference::isWildcard)) {
            return;
        }
        List<ColumnReference> expanded = new ArrayList<>();
        for (ColumnReference column : query.getProjectionColumns()) {
            if (!column.isWildcard()) {
                expanded.add(column);
            } else if (column.getTableName() != null) {
                TableBinding table = context.getTableBinding(column.getTableName());
                if (table == null) {
                    throw semanticError("Table '" + column.getTableName() + "' is not referenced in the query");
                }
                for (ColumnDefinition definition : table.getSchema().columns()) {
                    expanded.add(new ColumnReference(table.getAlias(), definition.name()));
                }
            } else {
                for (QueryTree child : query.getChildren()) {
                    expanded.addAll(visibleColumns(child, context));
                }
            }
        }
        query.setProjectionColumns(expanded);
    }

    private List<ColumnReference> visibleColumns(QueryTree node, BindingContext context) {
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN) {
            TableBinding table = context.getTableBinding(node.getTableName());
            return table.getSchema().columns().stream()
                .map(column -> new ColumnReference(table.getAlias(), column.name())).toList();
        }
        if (node.getNodeType() == QueryNodeType.PROJECT) {
            return node.getProjectionColumns().stream()
                .map(column -> {
                    ColumnBinding binding = resolveColumn(column, context);
                    return new ColumnReference(binding.getTableAlias(), binding.getColumnName());
                }).toList();
        }
        if (node.isNaturalJoin() && node.getChildren().size() == 2) {
            List<ColumnReference> left = visibleColumns(node.getChildren().get(0), context);
            List<ColumnReference> right = visibleColumns(node.getChildren().get(1), context);
            Set<String> common = new LinkedHashSet<>();
            for (ColumnReference l : left) {
                if (right.stream().anyMatch(r -> r.getColumnName().equalsIgnoreCase(l.getColumnName()))) {
                    common.add(l.getColumnName().toLowerCase(java.util.Locale.ROOT));
                }
            }
            List<ColumnReference> output = new ArrayList<>();
            for (String name : common) {
                output.add(left.stream().filter(c -> c.getColumnName().equalsIgnoreCase(name)).findFirst().orElseThrow());
            }
            for (ColumnReference column : left) {
                if (!common.contains(column.getColumnName().toLowerCase(java.util.Locale.ROOT))) output.add(column);
            }
            for (ColumnReference column : right) {
                if (!common.contains(column.getColumnName().toLowerCase(java.util.Locale.ROOT))) output.add(column);
            }
            return output;
        }
        List<ColumnReference> output = new ArrayList<>();
        for (QueryTree child : node.getChildren()) {
            output.addAll(visibleColumns(child, context));
        }
        return output;
    }

    private void resolveNaturalJoin(QueryTree query, BindingContext context) {
        if (query.getChildren().size() != 2) {
            throw semanticError("NATURAL JOIN requires two inputs");
        }
        List<ColumnReference> left = visibleColumns(query.getChildren().get(0), context);
        List<ColumnReference> right = visibleColumns(query.getChildren().get(1), context);
        ExpressionNode condition = null;
        for (ColumnReference column : left) {
            List<ColumnReference> matches = right.stream()
                .filter(r -> r.getColumnName().equalsIgnoreCase(column.getColumnName())).toList();
            if (matches.isEmpty()) continue;
            long leftMatches = left.stream()
                .filter(l -> l.getColumnName().equalsIgnoreCase(column.getColumnName())).count();
            if (matches.size() != 1 || leftMatches != 1) {
                throw semanticError("Ambiguous NATURAL JOIN column '" + column.getColumnName() + "'");
            }
            ColumnReference r = matches.get(0);
            ExpressionNode equality = ExpressionNode.comparison("=",
                ExpressionNode.column(column.getTableName(), column.getColumnName()),
                ExpressionNode.column(r.getTableName(), r.getColumnName()));
            condition = condition == null ? equality : ExpressionNode.logicalAnd(condition, equality);
        }
        query.setConditions(condition == null ? List.of() : List.of(new ExpressionTree(condition)));
    }

    private void collectAndRegisterTables(QueryTree node, BindingContext context) {
        if (node == null) {
            return;
        }
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN && node.getTableName() != null) {
            String tblName = node.getTableName();
            TableSchema schema = resolveTable(tblName);
            TableBinding binding = new TableBinding(tblName, tblName, schema);
            context.registerTableBinding(tblName, binding);
            for (ColumnDefinition col : schema.columns()) {
                ColumnReference ref = new ColumnReference(tblName, col.name());
                context.registerColumnBinding(ref, new ColumnBinding(tblName, tblName, col));
            }
        } else if (node.getTableName() != null && (
            node.getNodeType() == QueryNodeType.UPDATE ||
            node.getNodeType() == QueryNodeType.INSERT ||
            node.getNodeType() == QueryNodeType.DELETE ||
            node.getNodeType() == QueryNodeType.CREATE_INDEX)) {
            String tblName = node.getTableName();
            TableSchema schema = resolveTable(tblName);
            TableBinding binding = new TableBinding(tblName, tblName, schema);
            context.registerTableBinding(tblName, binding);
            for (ColumnDefinition col : schema.columns()) {
                ColumnReference ref = new ColumnReference(tblName, col.name());
                context.registerColumnBinding(ref, new ColumnBinding(tblName, tblName, col));
            }
        }

        for (QueryTree child : node.getChildren()) {
            collectAndRegisterTables(child, context);
        }
    }

}
