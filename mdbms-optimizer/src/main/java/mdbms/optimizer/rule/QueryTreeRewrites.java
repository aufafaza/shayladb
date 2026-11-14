package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.OrderByClause;
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

    // semua kolom yang muncul di ekspresi
    static List<ColumnReference> columnsIn(ExpressionTree expression) {
        List<ColumnReference> result = new ArrayList<>();
        if (expression != null) {
            collectColumns(expression.getRoot(), result);
        }
        return result;
    }

    // kolom yang dipakai node itu sendiri: kondisi, ORDER BY, daftar proyeksi
    static List<ColumnReference> columnsUsedBy(QueryTree node) {
        List<ColumnReference> result = new ArrayList<>();
        for (ExpressionTree condition : node.getConditions()) {
            result.addAll(columnsIn(condition));
        }
        for (OrderByClause clause : node.getOrderByClauses()) {
            result.add(clause.getField());
        }
        for (ColumnReference column : node.getProjectionColumns()) {
            if (!column.isWildcard()) {
                result.add(column);
            }
        }
        return result;
    }

    private static void collectColumns(ExpressionNode node, List<ColumnReference> out) {
        if (node == null) {
            return;
        }
        if (node.getType() == ExpressionNode.Type.COLUMN_REFERENCE) {
            out.add(new ColumnReference(node.getTableName(), node.getValue()));
        }
        collectColumns(node.getLeft(), out);
        collectColumns(node.getRight(), out);
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
