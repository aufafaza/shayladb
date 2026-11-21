package mdbms.optimizer.rule;

import java.util.List;
import java.util.Objects;

import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.cost.ICostEstimator;
import mdbms.optimizer.cost.PlanEstimate;


public class JoinAlgorithmSelectionRule implements IOptimizationRule {
    // JoinOperator di QP baru block nested loop;
    public static final List<PhysicalAlgorithm> QP_JOIN_ALGORITHMS = List.of(PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN);

    private final List<PhysicalAlgorithm> available;

    public JoinAlgorithmSelectionRule() {
        this(QP_JOIN_ALGORITHMS);
    }

    public JoinAlgorithmSelectionRule(List<PhysicalAlgorithm> available) {
        this.available = List.copyOf(Objects.requireNonNull(available, "available"));
        if (this.available.isEmpty()) {
            throw new IllegalArgumentException("At least one join algorithm is required");
        }
    }

    @Override
    public String getRuleName() {
        return "JoinAlgorithmSelection";
    }

    @Override
    public boolean matches(QueryTree node) {
        return node != null && node.getNodeType() == QueryNodeType.JOIN && node.getChildren().size() == 2
            && (node.getAlgorithm() == null || node.getAlgorithm() == PhysicalAlgorithm.DEFAULT);
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node)) {
            return node;
        }
        ICostEstimator estimator = context == null ? null : context.getCostEstimator();
        PhysicalAlgorithm chosen = null;
        if (estimator == null) {
            chosen = available.stream().filter(algorithm -> applicable(algorithm, node, context)).findFirst().orElse(null);
        } else {
            PlanEstimate left = estimator.estimateOutput(node.getChildren().get(0));
            PlanEstimate right = estimator.estimateOutput(node.getChildren().get(1));
            int best = Integer.MAX_VALUE;
            for (PhysicalAlgorithm algorithm : available) {
                if (!applicable(algorithm, node, context)) {
                    continue;
                }
                int cost = estimator.calculateNodeCost(node, algorithm, left, right);
                // kalau seri, urutan di daftar yang menang
                if (cost < best) {
                    best = cost;
                    chosen = algorithm;
                }
            }
        }
        if (chosen == null) {
            return node;
        }
        QueryTree result = QueryTreeRewrites.withChildren(node, node.getChildren());
        result.setAlgorithm(chosen);
        return result;
    }

    // hash/merge butuh kondisi kolom = kolom, index nested loop butuh index di sisi kanan
    static boolean applicable(PhysicalAlgorithm algorithm, QueryTree join, OptimizationContext context) {
        return switch (algorithm) {
            case NESTED_LOOP_JOIN, BLOCK_NESTED_LOOP_JOIN -> true;
            case HASH_JOIN, MERGE_JOIN -> hasEquiJoin(join);
            case INDEX_NESTED_LOOP_JOIN -> hasIndexedInner(join, context);
            default -> false;
        };
    }

    private static boolean hasEquiJoin(QueryTree join) {
        for (ExpressionTree condition : join.getConditions()) {
            for (ExpressionTree conjunct : QueryTreeRewrites.splitConjuncts(condition)) {
                if (columnEquality(conjunct.getRoot())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean columnEquality(ExpressionNode node) {
        return node != null && node.getType() == ExpressionNode.Type.COMPARISON && "=".equals(node.getValue())
            && node.getLeft().getType() == ExpressionNode.Type.COLUMN_REFERENCE
            && node.getRight().getType() == ExpressionNode.Type.COLUMN_REFERENCE;
    }

    // sisi kanan harus scan tabel langsung yang punya index di kolom kesamaan join
    private static boolean hasIndexedInner(QueryTree join, OptimizationContext context) {
        QueryTree inner = join.getChildren().get(1);
        if (context == null || inner.getNodeType() != QueryNodeType.TABLE_SCAN) {
            return false;
        }
        String table = inner.getTableName();
        for (ExpressionTree condition : join.getConditions()) {
            for (ExpressionTree conjunct : QueryTreeRewrites.splitConjuncts(condition)) {
                ExpressionNode root = conjunct.getRoot();
                if (!columnEquality(root)) {
                    continue;
                }
                for (ExpressionNode side : List.of(root.getLeft(), root.getRight())) {
                    if (table.equalsIgnoreCase(side.getTableName()) && context.getIndexes(table).stream()
                        .anyMatch(index -> index.columnName().equalsIgnoreCase(side.getValue()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
