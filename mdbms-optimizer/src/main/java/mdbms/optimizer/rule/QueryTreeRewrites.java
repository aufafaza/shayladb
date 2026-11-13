package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;

// helper rebuild node, children QueryTree ga bisa diubah langsung
final class QueryTreeRewrites {
    private QueryTreeRewrites() {
    }

    // copy node dengan children baru
    static QueryTree withChildren(QueryTree node, List<QueryTree> children) {
        Objects.requireNonNull(node, "node");
        QueryTree copy = new QueryTree(
            node.getNodeType(),
            node.getAlgorithm(),
            node.getTableName(),
            node.getConditions(),
            children,
            node.getEstimatedCost(),
            node.getProjectionColumns(),
            node.isNaturalJoin()
        );
        if (node.getNodeType() == QueryNodeType.SORT) {
            copy.setOrderByClauses(node.getOrderByClauses());
        }
        if (node.getNodeType() == QueryNodeType.LIMIT && node.getLimit() != null) {
            copy.setLimit(node.getLimit());
        }
        if (node.getUpdateColumn() != null) {
            copy.setUpdateAssignment(node.getUpdateColumn(), node.getUpdateExpression());
        }
        if (!node.getInsertValues().isEmpty()) {
            copy.setInsertValues(node.getInsertValues());
        }
        if (node.getIndexName() != null) {
            copy.setIndexDefinition(node.getIndexName(), node.getIndexColumn());
        }
        return copy;
    }

    // SELECT dengan satu kondisi
    static QueryTree selection(ExpressionTree condition, QueryTree child) {
        return new QueryTree(QueryNodeType.SELECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(condition), List.of(child), 0);
    }

    // pecah a AND b AND c jadi [a, b, c]
    static List<ExpressionTree> splitConjuncts(ExpressionTree condition) {
        List<ExpressionTree> result = new ArrayList<>();
        if (condition != null && condition.getRoot() != null) {
            collectConjuncts(condition.getRoot(), result);
        }
        return result;
    }

    private static void collectConjuncts(ExpressionNode node, List<ExpressionTree> out) {
        if (node.getType() == ExpressionNode.Type.LOGICAL_AND) {
            collectConjuncts(node.getLeft(), out);
            collectConjuncts(node.getRight(), out);
        } else {
            out.add(new ExpressionTree(node));
        }
    }
}
