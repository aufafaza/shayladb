package mdbms.models;

import java.util.List;

public class QueryTree {
    private final QueryNodeType nodeType;
    private PhysicalAlgorithm algorithm;
    private final String value;
    private final List<Condition> conditions;
    private final List<QueryTree> children;
    private int estimatedCost;

    public QueryTree(
        QueryNodeType nodeType,
        PhysicalAlgorithm algorithm,
        String value,
        List<Condition> conditions,
        List<QueryTree> children,
        int estimatedCost
    ) {
        this.nodeType = nodeType;
        this.algorithm = algorithm;
        this.value = value;
        this.conditions = List.copyOf(conditions);
        this.children = List.copyOf(children);
        this.estimatedCost = estimatedCost;
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

    public String getValue() {
        return value;
    }

    public List<Condition> getConditions() {
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
