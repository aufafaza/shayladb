package mdbms.models.query;

import java.util.List;

public class QueryTree {
    private final QueryNodeType nodeType;
    private PhysicalAlgorithm algorithm;
    private final String tableName;
    private final List<ExpressionTree> conditions;
    private final List<QueryTree> children;
    private int estimatedCost;
    private final List<ColumnReference> projectionColumns;
    private final boolean naturalJoin;

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
