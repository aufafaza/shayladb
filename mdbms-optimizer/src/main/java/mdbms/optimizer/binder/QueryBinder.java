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
                    bindExpression(cond, context);
                }
            }
            case SELECT -> {
                for (ExpressionTree cond : node.getConditions()) {
                    bindExpression(cond, context);
                }
            }
            case SORT -> {
                for (OrderByClause clause : node.getOrderByClauses()) {
                    if (clause.getField() != null) {
                        ColumnBinding cb = resolveColumn(clause.getField(), context);
                        context.registerColumnBinding(clause.getField(), cb);
                    }
                }
            }
            case PROJECT -> {
                expandSelectStar(node, context);
                for (ColumnReference col : node.getProjectionColumns()) {
                    if (!col.isWildcard()) {
                        ColumnBinding cb = resolveColumn(col, context);
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
                    bindExpression(node.getUpdateExpression(), context);
                }
                for (ExpressionTree cond : node.getConditions()) {
                    bindExpression(cond, context);
                }
            }
            case INSERT -> {
                for (ExpressionTree val : node.getInsertValues()) {
                    bindExpression(val, context);
                }
            }
            case DELETE -> {
                for (ExpressionTree cond : node.getConditions()) {
                    bindExpression(cond, context);
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

    private void bindExpression(ExpressionTree expression, BindingContext context) {
        if (expression == null || expression.getRoot() == null) {
            return;
        }
        bindExpressionNode(expression.getRoot(), context);
    }

    private void bindExpressionNode(ExpressionNode node, BindingContext context) {
        if (node == null) {
            return;
        }
        if (node.getType() == ExpressionNode.Type.COLUMN_REFERENCE) {
            ColumnReference ref = new ColumnReference(node.getTableName(), node.getValue());
            ColumnBinding cb = resolveColumn(ref, context);
            context.registerColumnBinding(ref, cb);
            if (ref.getTableName() != null) {
                ColumnReference unqual = new ColumnReference(null, ref.getColumnName());
                if (context.getColumnBinding(unqual) == null) {
                    context.registerColumnBinding(unqual, cb);
                }
            }
        }
        bindExpressionNode(node.getLeft(), context);
        bindExpressionNode(node.getRight(), context);
    }

    private void expandSelectStar(QueryTree query, BindingContext context) {
        if (query == null || query.getNodeType() != QueryNodeType.PROJECT) {
            return;
        }
        List<ColumnReference> current = query.getProjectionColumns();
        boolean hasWildcard = current.stream().anyMatch(ColumnReference::isWildcard);
        if (!hasWildcard) {
            return;
        }

        boolean isNatural = hasNaturalJoin(query);
        Set<String> naturalCommonCols = isNatural ? findNaturalJoinCommonColumns(query, context) : Set.of();

        List<ColumnReference> expanded = new ArrayList<>();
        List<String> tableOrder = collectTableNames(query);

        if (isNatural && !naturalCommonCols.isEmpty()) {
            // Common columns first without duplicate column
            for (String commonCol : naturalCommonCols) {
                boolean added = false;
                for (String tbl : tableOrder) {
                    TableBinding tb = context.getTableBinding(tbl);
                    if (tb != null) {
                        for (ColumnDefinition cd : tb.getSchema().columns()) {
                            if (cd.name().equalsIgnoreCase(commonCol)) {
                                ColumnReference ref = new ColumnReference(tb.getAlias(), cd.name());
                                expanded.add(ref);
                                context.registerColumnBinding(ref, new ColumnBinding(tb.getTableName(), tb.getAlias(), cd));
                                added = true;
                                break;
                            }
                        }
                    }
                    if (added) {
                        break;
                    }
                }
            }
            // Followed by unshared columns of each table
            for (String tbl : tableOrder) {
                TableBinding tb = context.getTableBinding(tbl);
                if (tb != null) {
                    for (ColumnDefinition cd : tb.getSchema().columns()) {
                        if (!naturalCommonCols.contains(cd.name().toLowerCase())) {
                            ColumnReference ref = new ColumnReference(tb.getAlias(), cd.name());
                            expanded.add(ref);
                            context.registerColumnBinding(ref, new ColumnBinding(tb.getTableName(), tb.getAlias(), cd));
                        }
                    }
                }
            }
        } else {
            // Standard expansion: each table in written order, each column in schema order
            for (String tbl : tableOrder) {
                TableBinding tb = context.getTableBinding(tbl);
                if (tb != null) {
                    for (ColumnDefinition cd : tb.getSchema().columns()) {
                        ColumnReference ref = new ColumnReference(tb.getAlias(), cd.name());
                        expanded.add(ref);
                        context.registerColumnBinding(ref, new ColumnBinding(tb.getTableName(), tb.getAlias(), cd));
                    }
                }
            }
        }

        query.setProjectionColumns(expanded);
    }

    private void resolveNaturalJoin(QueryTree query, BindingContext context) {
        if (query == null || query.getNodeType() != QueryNodeType.JOIN || !query.isNaturalJoin()) {
            return;
        }
        List<QueryTree> children = query.getChildren();
        if (children.size() < 2) {
            return;
        }
        QueryTree leftChild = children.get(0);
        QueryTree rightChild = children.get(1);

        List<String> leftTables = collectTableNames(leftChild);
        List<String> rightTables = collectTableNames(rightChild);

        List<ColumnDefinition> commonColumns = new ArrayList<>();
        List<String> commonColNames = new ArrayList<>();

        for (String leftTbl : leftTables) {
            TableBinding leftBinding = context.getTableBinding(leftTbl);
            if (leftBinding == null) continue;
            for (ColumnDefinition leftCol : leftBinding.getSchema().columns()) {
                for (String rightTbl : rightTables) {
                    TableBinding rightBinding = context.getTableBinding(rightTbl);
                    if (rightBinding == null) continue;
                    for (ColumnDefinition rightCol : rightBinding.getSchema().columns()) {
                        if (leftCol.name().equalsIgnoreCase(rightCol.name())) {
                            if (!commonColNames.contains(leftCol.name().toLowerCase())) {
                                commonColNames.add(leftCol.name().toLowerCase());
                                commonColumns.add(leftCol);
                            }
                        }
                    }
                }
            }
        }

        if (!commonColumns.isEmpty()) {
            ExpressionNode combinedCondition = null;
            for (String leftTbl : leftTables) {
                TableBinding leftBinding = context.getTableBinding(leftTbl);
                if (leftBinding == null) continue;
                for (String rightTbl : rightTables) {
                    TableBinding rightBinding = context.getTableBinding(rightTbl);
                    if (rightBinding == null) continue;
                    for (ColumnDefinition col : commonColumns) {
                        boolean inLeft = leftBinding.getSchema().columns().stream()
                            .anyMatch(c -> c.name().equalsIgnoreCase(col.name()));
                        boolean inRight = rightBinding.getSchema().columns().stream()
                            .anyMatch(c -> c.name().equalsIgnoreCase(col.name()));
                        if (inLeft && inRight) {
                            ExpressionNode leftNode = ExpressionNode.column(leftBinding.getAlias(), col.name());
                            ExpressionNode rightNode = ExpressionNode.column(rightBinding.getAlias(), col.name());
                            ExpressionNode eq = ExpressionNode.comparison("=", leftNode, rightNode);
                            if (combinedCondition == null) {
                                combinedCondition = eq;
                            } else {
                                combinedCondition = ExpressionNode.logicalAnd(combinedCondition, eq);
                            }
                        }
                    }
                }
            }
            if (combinedCondition != null) {
                query.setConditions(List.of(new ExpressionTree(combinedCondition)));
            }
        }
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

    private List<String> collectTableNames(QueryTree node) {
        List<String> tables = new ArrayList<>();
        collectTableNamesHelper(node, tables);
        return tables;
    }

    private void collectTableNamesHelper(QueryTree node, List<String> tables) {
        if (node == null) return;
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN && node.getTableName() != null) {
            if (!tables.contains(node.getTableName())) {
                tables.add(node.getTableName());
            }
        } else if (node.getTableName() != null && (
            node.getNodeType() == QueryNodeType.UPDATE ||
            node.getNodeType() == QueryNodeType.INSERT ||
            node.getNodeType() == QueryNodeType.DELETE ||
            node.getNodeType() == QueryNodeType.CREATE_INDEX)) {
            if (!tables.contains(node.getTableName())) {
                tables.add(node.getTableName());
            }
        }
        for (QueryTree child : node.getChildren()) {
            collectTableNamesHelper(child, tables);
        }
    }

    private boolean hasNaturalJoin(QueryTree node) {
        if (node == null) return false;
        if (node.getNodeType() == QueryNodeType.JOIN && node.isNaturalJoin()) {
            return true;
        }
        for (QueryTree child : node.getChildren()) {
            if (hasNaturalJoin(child)) return true;
        }
        return false;
    }

    private Set<String> findNaturalJoinCommonColumns(QueryTree node, BindingContext context) {
        Set<String> common = new LinkedHashSet<>();
        findNaturalJoinCommonHelper(node, context, common);
        return common;
    }

    private void findNaturalJoinCommonHelper(QueryTree node, BindingContext context, Set<String> common) {
        if (node == null) return;
        if (node.getNodeType() == QueryNodeType.JOIN && node.isNaturalJoin()) {
            if (node.getChildren().size() >= 2) {
                List<String> leftTables = collectTableNames(node.getChildren().get(0));
                List<String> rightTables = collectTableNames(node.getChildren().get(1));
                for (String l : leftTables) {
                    TableBinding tbL = context.getTableBinding(l);
                    if (tbL == null) continue;
                    for (ColumnDefinition cdL : tbL.getSchema().columns()) {
                        for (String r : rightTables) {
                            TableBinding tbR = context.getTableBinding(r);
                            if (tbR == null) continue;
                            for (ColumnDefinition cdR : tbR.getSchema().columns()) {
                                if (cdL.name().equalsIgnoreCase(cdR.name())) {
                                    common.add(cdL.name().toLowerCase());
                                }
                            }
                        }
                    }
                }
            }
        }
        for (QueryTree child : node.getChildren()) {
            findNaturalJoinCommonHelper(child, context, common);
        }
    }
}
