package mdbms.optimizer.rule;

import java.util.List;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.OrderByClause;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.ColumnBinding;

/** Lowers bound natural joins to explicit inner joins before join ordering. */
public class NaturalJoinNormalizationRule implements IOptimizationRule {
    @Override
    public String getRuleName() {
        return "NaturalJoinNormalization";
    }

    @Override
    public boolean matches(QueryTree node) {
        return node != null && (node.isNaturalJoin()
            || QueryTreeRewrites.columnsUsedBy(node).stream()
                .anyMatch(column -> column.getTableName() == null && !column.isWildcard()));
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node) || context == null || context.getBindingContext() == null) {
            return node;
        }
        BindingContext bindings = context.getBindingContext();
        QueryTree result = QueryTreeRewrites.withChildren(node, node.getChildren());
            result.setConditions(node.getConditions().stream()
            .map(condition -> new ExpressionTree(qualify(condition.getRoot(), bindings))).toList());
        result.setProjectionColumns(node.getProjectionColumns().stream()
            .map(column -> qualify(column, bindings)).toList());
        if (node.getNodeType() == QueryNodeType.SORT) {
            result.setOrderByClauses(node.getOrderByClauses().stream()
                .map(clause -> new OrderByClause(qualify(clause.getField(), bindings), clause.getDirection())).toList());
        }
        if (node.isNaturalJoin()) {
            if (node.getChildren().size() != 2) {
                return node;
            }
            if (result.getConditions().isEmpty()) {
                return new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null,
                    List.of(), result.getChildren(), 0);
            }
            result.setNaturalJoin(false);
            result.setAlgorithm(PhysicalAlgorithm.DEFAULT);
        }
        return result;
    }

    private static ColumnReference qualify(ColumnReference column, BindingContext bindings) {
        ColumnBinding binding = bindings.getColumnBinding(column);
        return binding == null || column.isWildcard() ? column
            : new ColumnReference(binding.getTableAlias(), binding.getColumnName());
    }

    private static ExpressionNode qualify(ExpressionNode node, BindingContext bindings) {
        if (node == null) return null;
        return switch (node.getType()) {
            case COLUMN_REFERENCE -> {
                ColumnReference column = qualify(new ColumnReference(node.getTableName(), node.getValue()), bindings);
                yield ExpressionNode.column(column.getTableName(), column.getColumnName());
            }
            case COMPARISON -> ExpressionNode.comparison(node.getValue(),
                qualify(node.getLeft(), bindings), qualify(node.getRight(), bindings));
            case BINARY_OPERATION -> ExpressionNode.binary(node.getValue(),
                qualify(node.getLeft(), bindings), qualify(node.getRight(), bindings));
            case LOGICAL_AND -> ExpressionNode.logicalAnd(
                qualify(node.getLeft(), bindings), qualify(node.getRight(), bindings));
            default -> node;
        };
    }
}
