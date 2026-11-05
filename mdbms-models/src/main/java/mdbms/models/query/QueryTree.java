package mdbms.models.query;

import java.util.List;
import java.util.Objects;

public class QueryTree {
    private final QueryNodeType nodeType;
    private PhysicalAlgorithm algorithm;
    private final String tableName;
    private final List<ExpressionTree> conditions;
    private final List<QueryTree> children;
    private int estimatedCost;
    private final List<ColumnReference> projectionColumns;
    private final boolean naturalJoin;
    private List<OrderByClause> orderByClauses = List.of();
    private ColumnReference updateColumn;
    private ExpressionTree updateExpression;
    private List<ExpressionTree> insertValues = List.of();
    private String indexName;
    private ColumnReference indexColumn;

    public QueryTree(
        QueryNodeType nodeType,
        PhysicalAlgorithm algorithm,
        String tableName,
        List<ExpressionTree> conditions,
        List<QueryTree> children,
        int estimatedCost
    ) {
        this(nodeType, algorithm, tableName, conditions, children, estimatedCost, List.of(), false);
    }

    /** Additional payloads missing from the diagram: SELECT items and the natural-join marker. */
    public QueryTree(
        QueryNodeType nodeType,
        PhysicalAlgorithm algorithm,
        String tableName,
        List<ExpressionTree> conditions,
        List<QueryTree> children,
        int estimatedCost,
        List<ColumnReference> projectionColumns,
        boolean naturalJoin
    ) {
        if (naturalJoin && nodeType != QueryNodeType.JOIN) {
            throw new IllegalArgumentException("Only JOIN nodes can be marked as natural joins");
        }
        this.nodeType = nodeType;
        this.algorithm = algorithm;
        this.tableName = tableName;
        this.conditions = List.copyOf(conditions);
        this.children = List.copyOf(children);
        this.estimatedCost = estimatedCost;
        this.projectionColumns = List.copyOf(projectionColumns);
        this.naturalJoin = naturalJoin;
    }

    public List<OrderByClause> getOrderByClauses() {
        return orderByClauses;
    }

    public ColumnReference getUpdateColumn() {
        return updateColumn;
    }

    public ExpressionTree getUpdateExpression() {
        return updateExpression;
    }

    /** One SET assignment is sufficient for the required UPDATE grammar. */
    public void setUpdateAssignment(ColumnReference column, ExpressionTree expression) {
        requireNodeType(QueryNodeType.UPDATE);
        requireConcreteColumn(column);
        Objects.requireNonNull(expression);
        this.updateColumn = column;
        this.updateExpression = expression;
    }

    public List<ExpressionTree> getInsertValues() {
        return insertValues;
    }

    public void setInsertValues(List<ExpressionTree> values) {
        requireNodeType(QueryNodeType.INSERT);
        List<ExpressionTree> copy = List.copyOf(values);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("INSERT requires at least one value");
        }
        this.insertValues = copy;
    }

    public String getIndexName() {
        return indexName;
    }

    public ColumnReference getIndexColumn() {
        return indexColumn;
    }

    public void setIndexDefinition(String name, ColumnReference column) {
        requireNodeType(QueryNodeType.CREATE_INDEX);
        Objects.requireNonNull(name);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Index name must not be blank");
        }
        requireConcreteColumn(column);
        this.indexName = name;
        this.indexColumn = column;
    }

    private void requireNodeType(QueryNodeType expected) {
        if (nodeType != expected) {
            throw new IllegalStateException("This payload requires a " + expected + " node");
        }
    }

    private void requireConcreteColumn(ColumnReference column) {
        Objects.requireNonNull(column);
        if (column.isWildcard()) {
            throw new IllegalArgumentException("Expected a column name, not a wildcard");
        }
    }

    /** Attaches sort keys without changing the existing six/eight-parameter constructors */
    public void setOrderByClauses(List<OrderByClause> orderByClauses) {
        if (nodeType != QueryNodeType.SORT) {
            throw new IllegalStateException("Only SORT nodes can carry ORDER BY clauses");
        }
        this.orderByClauses = List.copyOf(orderByClauses);
    }

    //Ordered SELECT items, including an unresolved wildcard for SELECT
    public List<ColumnReference> getProjectionColumns() {
        return projectionColumns;
    }

    //A natural JOIN is resolved from catalog columns by the binder.
    public boolean isNaturalJoin() {
        return naturalJoin;
    }

    public QueryNodeType getNodeType() {
        return nodeType;
    }

    public PhysicalAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(PhysicalAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public String getTableName() {
        return tableName;
    }

    /** @deprecated Use getTableName(); projections are in getProjectionColumns(). */
    @Deprecated
    public String getValue() {
        return tableName;
    }

    //Full ON/WHERE expression trees. see class diagram.
    public List<ExpressionTree> getConditions() {
        return conditions;
    }

    public List<QueryTree> getChildren() {
        return children;
    }

    public int getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(int estimatedCost) {
        this.estimatedCost = estimatedCost;
    }
}
