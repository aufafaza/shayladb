package mdbms.optimizer.cost;

import java.util.LinkedHashMap;
import java.util.Map;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;

public class CardinalityEstimator {

    public PlanEstimate estimateSelection(PlanEstimate input, ExpressionTree predicate) {
        if (input == null) {
            return new PlanEstimate(0L, 0L, Map.of());
        }
        if (predicate == null || predicate.getRoot() == null) {
            return input;
        }

        double selectivity = computeSelectivity(predicate.getRoot(), input);
        selectivity = Math.max(0.0001, Math.min(1.0, selectivity));

        long inputRows = input.getEstimatedRows();
        long inputBlocks = input.getEstimatedBlocks();

        long estimatedRows = Math.round(inputRows * selectivity);
        if (inputRows > 0 && estimatedRows == 0) {
            estimatedRows = 1L;
        }

        long estimatedBlocks = Math.round(inputBlocks * selectivity);
        if (inputBlocks > 0 && estimatedBlocks == 0) {
            estimatedBlocks = 1L;
        }
        if (estimatedRows == 0) {
            estimatedBlocks = 0L;
        }

        Map<String, Long> distinctValues = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : input.getAllDistinctValues().entrySet()) {
            distinctValues.put(entry.getKey(), Math.min(entry.getValue(), estimatedRows));
        }

        // For equality condition 'col = val', V(col, result) = 1
        recordEqualityColumnBounds(predicate.getRoot(), distinctValues);

        return new PlanEstimate(estimatedRows, estimatedBlocks, distinctValues);
    }

    public PlanEstimate estimateCartesianProduct(PlanEstimate left, PlanEstimate right) {
        if (left == null || right == null) {
            return new PlanEstimate(0L, 0L, Map.of());
        }

        long leftRows = left.getEstimatedRows();
        long rightRows = right.getEstimatedRows();
        long estimatedRows = leftRows * rightRows;

        long leftBlocks = left.getEstimatedBlocks();
        long rightBlocks = right.getEstimatedBlocks();

        long estimatedBlocks;
        if (leftRows == 0 || rightRows == 0) {
            estimatedBlocks = 0L;
        } else {
            // Block allocation for combined tuples: b_R * n_S + b_S * n_R
            estimatedBlocks = Math.max(1L, leftBlocks * rightRows + rightBlocks * leftRows);
        }

        Map<String, Long> distinctValues = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : left.getAllDistinctValues().entrySet()) {
            distinctValues.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Long> e : right.getAllDistinctValues().entrySet()) {
            if (distinctValues.containsKey(e.getKey())) {
                distinctValues.put(e.getKey(), Math.max(distinctValues.get(e.getKey()), e.getValue()));
            } else {
                distinctValues.put(e.getKey(), e.getValue());
            }
        }

        return new PlanEstimate(estimatedRows, estimatedBlocks, distinctValues);
    }

    public PlanEstimate estimateJoin(PlanEstimate left, PlanEstimate right, ExpressionTree predicate) {
        if (left == null || right == null) {
            return new PlanEstimate(0L, 0L, Map.of());
        }
        if (predicate == null || predicate.getRoot() == null) {
            return estimateCartesianProduct(left, right);
        }

        long leftRows = left.getEstimatedRows();
        long rightRows = right.getEstimatedRows();
        if (leftRows == 0 || rightRows == 0) {
            return new PlanEstimate(0L, 0L, Map.of());
        }

        double joinSelectivity = computeJoinSelectivity(predicate.getRoot(), left, right);
        joinSelectivity = Math.max(0.000001, Math.min(1.0, joinSelectivity));

        long estimatedRows = Math.round(leftRows * rightRows * joinSelectivity);
        if (estimatedRows == 0) {
            estimatedRows = 1L;
        }

        long leftBlocks = left.getEstimatedBlocks();
        long rightBlocks = right.getEstimatedBlocks();

        double bytesPerTupleRatio = ((double) leftBlocks / leftRows) + ((double) rightBlocks / rightRows);
        long estimatedBlocks = Math.max(1L, Math.round(estimatedRows * bytesPerTupleRatio));

        Map<String, Long> distinctValues = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : left.getAllDistinctValues().entrySet()) {
            distinctValues.put(entry.getKey(), Math.min(entry.getValue(), estimatedRows));
        }
        for (Map.Entry<String, Long> entry : right.getAllDistinctValues().entrySet()) {
            if (distinctValues.containsKey(entry.getKey())) {
                distinctValues.put(entry.getKey(), Math.min(Math.min(distinctValues.get(entry.getKey()), entry.getValue()), estimatedRows));
            } else {
                distinctValues.put(entry.getKey(), Math.min(entry.getValue(), estimatedRows));
            }
        }

        return new PlanEstimate(estimatedRows, estimatedBlocks, distinctValues);
    }

    private double computeSelectivity(ExpressionNode node, PlanEstimate input) {
        if (node == null) {
            return 1.0;
        }
        if (node.getType() == ExpressionNode.Type.LOGICAL_AND) {
            double leftSel = computeSelectivity(node.getLeft(), input);
            double rightSel = computeSelectivity(node.getRight(), input);
            return leftSel * rightSel;
        }
        if (node.getType() == ExpressionNode.Type.COMPARISON) {
            String op = node.getValue();
            ExpressionNode left = node.getLeft();
            ExpressionNode right = node.getRight();

            if ("=".equals(op)) {
                return computeEqualitySelectivity(left, right, input);
            }
            if ("<>".equals(op) || "!=".equals(op)) {
                return Math.max(0.0, 1.0 - computeEqualitySelectivity(left, right, input));
            }
            if ("<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
                // Heuristik seleksi rentang tanpa informasi min/max: 1/3 (Silberschatz)
                return 1.0 / 3.0;
            }
        }
        return 0.5;
    }

    private double computeEqualitySelectivity(ExpressionNode left, ExpressionNode right, PlanEstimate input) {
        boolean leftIsCol = left != null && left.getType() == ExpressionNode.Type.COLUMN_REFERENCE;
        boolean rightIsCol = right != null && right.getType() == ExpressionNode.Type.COLUMN_REFERENCE;

        if (leftIsCol && !rightIsCol) {
            long v = input.getDistinctValues(left.getValue());
            return v > 0 ? (1.0 / v) : 0.1;
        }
        if (!leftIsCol && rightIsCol) {
            long v = input.getDistinctValues(right.getValue());
            return v > 0 ? (1.0 / v) : 0.1;
        }
        if (leftIsCol && rightIsCol) {
            long v1 = input.getDistinctValues(left.getValue());
            long v2 = input.getDistinctValues(right.getValue());
            long maxV = Math.max(v1, v2);
            return maxV > 0 ? (1.0 / maxV) : 0.1;
        }
        return 0.1;
    }

    private double computeJoinSelectivity(ExpressionNode node, PlanEstimate left, PlanEstimate right) {
        if (node == null) {
            return 1.0;
        }
        if (node.getType() == ExpressionNode.Type.LOGICAL_AND) {
            double s1 = computeJoinSelectivity(node.getLeft(), left, right);
            double s2 = computeJoinSelectivity(node.getRight(), left, right);
            return s1 * s2;
        }
        if (node.getType() == ExpressionNode.Type.COMPARISON && "=".equals(node.getValue())) {
            ExpressionNode n1 = node.getLeft();
            ExpressionNode n2 = node.getRight();
            if (n1 != null && n2 != null
                && n1.getType() == ExpressionNode.Type.COLUMN_REFERENCE
                && n2.getType() == ExpressionNode.Type.COLUMN_REFERENCE) {

                long vLeft = left.getDistinctValues(n1.getValue());
                if (vLeft == 0) {
                    vLeft = left.getDistinctValues(n2.getValue());
                }
                long vRight = right.getDistinctValues(n2.getValue());
                if (vRight == 0) {
                    vRight = right.getDistinctValues(n1.getValue());
                }

                long maxV = Math.max(vLeft, vRight);
                if (maxV > 0) {
                    return 1.0 / maxV;
                }
                // Fallback join selectivity based on table sizes
                long maxRows = Math.max(left.getEstimatedRows(), right.getEstimatedRows());
                return maxRows > 0 ? (1.0 / Math.max(10.0, maxRows)) : 0.1;
            }
        }
        return computeSelectivity(node, left);
    }

    private void recordEqualityColumnBounds(ExpressionNode node, Map<String, Long> distinctValues) {
        if (node == null) {
            return;
        }
        if (node.getType() == ExpressionNode.Type.LOGICAL_AND) {
            recordEqualityColumnBounds(node.getLeft(), distinctValues);
            recordEqualityColumnBounds(node.getRight(), distinctValues);
        } else if (node.getType() == ExpressionNode.Type.COMPARISON && "=".equals(node.getValue())) {
            ExpressionNode left = node.getLeft();
            ExpressionNode right = node.getRight();
            if (left != null && left.getType() == ExpressionNode.Type.COLUMN_REFERENCE
                && right != null && right.getType() != ExpressionNode.Type.COLUMN_REFERENCE) {
                distinctValues.put(left.getValue(), 1L);
            } else if (right != null && right.getType() == ExpressionNode.Type.COLUMN_REFERENCE
                && left != null && left.getType() != ExpressionNode.Type.COLUMN_REFERENCE) {
                distinctValues.put(right.getValue(), 1L);
            }
        }
    }
}
