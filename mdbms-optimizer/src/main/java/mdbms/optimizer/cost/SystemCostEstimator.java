package mdbms.optimizer.cost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import mdbms.api.IStatisticsProvider;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.Statistic;

public class SystemCostEstimator implements ICostEstimator {
    private final IStatisticsProvider statisticsProvider;
    private final CardinalityEstimator cardinalityEstimator;

    public SystemCostEstimator(IStatisticsProvider statisticsProvider, CardinalityEstimator cardinalityEstimator) {
        this.statisticsProvider = statisticsProvider;
        this.cardinalityEstimator = Objects.requireNonNull(cardinalityEstimator, "cardinalityEstimator must not be null");
    }

    @Override
    public int calculateTotalCost(QueryTree root) {
        if (root == null) {
            return 0;
        }
        return calculateSubtreeCost(root);
    }

    private int calculateSubtreeCost(QueryTree node) {
        if (node == null) {
            return 0;
        }

        int childrenTotal = 0;
        for (QueryTree child : node.getChildren()) {
            childrenTotal += calculateSubtreeCost(child);
        }

        PlanEstimate leftEst = null;
        PlanEstimate rightEst = null;
        List<QueryTree> children = node.getChildren();
        if (children.size() >= 1) {
            leftEst = estimateOutput(children.get(0));
        }
        if (children.size() >= 2) {
            rightEst = estimateOutput(children.get(1));
        }

        PhysicalAlgorithm algo = node.getAlgorithm();
        if (algo == null || algo == PhysicalAlgorithm.DEFAULT) {
            algo = inferDefaultAlgorithm(node);
        }

        int nodeCost = calculateNodeCost(node, algo, leftEst, rightEst);
        node.setEstimatedCost(nodeCost);

        return childrenTotal + nodeCost;
    }

    @Override
    public int calculateNodeCost(QueryTree node, PhysicalAlgorithm algorithm, PlanEstimate left, PlanEstimate right) {
        if (node == null) {
            return 0;
        }
        PhysicalAlgorithm algo = algorithm;
        if (algo == null || algo == PhysicalAlgorithm.DEFAULT) {
            algo = inferDefaultAlgorithm(node);
        }

        switch (algo) {
            case LINEAR_SCAN -> {
                if (left != null && left.getEstimatedBlocks() > 0) {
                    return (int) left.getEstimatedBlocks();
                }
                Statistic stat = getTableStatistics(node.getTableName());
                if (stat != null) {
                    return Math.max(1, stat.br());
                }
                return 1;
            }
            case INDEX_SCAN -> {
                Statistic stat = getTableStatistics(node.getTableName());
                int br = stat != null ? Math.max(1, stat.br()) : 1;
                // Index lookup: Tree height (typically 2-3) + data blocks read
                double selectivity = 0.1;
                if (!node.getConditions().isEmpty()) {
                    // selektivitas dihitung dari isi tabel sebelum disaring
                    PlanEstimate input = tableEstimate(node);
                    PlanEstimate filtered = cardinalityEstimator.estimateSelection(input, node.getConditions().get(0));
                    if (input.getEstimatedRows() > 0) {
                        selectivity = (double) filtered.getEstimatedRows() / input.getEstimatedRows();
                    }
                }
                int dataBlocksRead = (int) Math.max(1L, Math.round(br * selectivity));
                int indexHeight = 2;
                return Math.min(br, indexHeight + dataBlocksRead);
            }
            case NESTED_LOOP_JOIN -> {
                long bR = left != null ? left.getEstimatedBlocks() : 1L;
                long nR = left != null ? left.getEstimatedRows() : 1L;
                long bS = right != null ? right.getEstimatedBlocks() : 1L;
                return (int) Math.max(1L, bR + nR * bS);
            }
            case BLOCK_NESTED_LOOP_JOIN -> {
                long bR = left != null ? left.getEstimatedBlocks() : 1L;
                long bS = right != null ? right.getEstimatedBlocks() : 1L;
                // Silberschatz: B(R) + ceil(B(R) / (M - 2)) * B(S), standard buffer pool chunk size (M-2) = 3
                long chunks = (long) Math.ceil((double) bR / 3.0);
                return (int) Math.max(1L, bR + chunks * bS);
            }
            case INDEX_NESTED_LOOP_JOIN -> {
                long bR = left != null ? left.getEstimatedBlocks() : 1L;
                long nR = left != null ? left.getEstimatedRows() : 1L;
                int cIndex = 3; // B+ tree search + record fetch
                return (int) Math.max(1L, bR + nR * cIndex);
            }
            case HASH_JOIN -> {
                long bR = left != null ? left.getEstimatedBlocks() : 1L;
                long bS = right != null ? right.getEstimatedBlocks() : 1L;
                // Silberschatz: 3 * (B(R) + B(S))
                return (int) Math.max(1L, 3 * (bR + bS));
            }
            case MERGE_JOIN -> {
                long bR = left != null ? left.getEstimatedBlocks() : 1L;
                long bS = right != null ? right.getEstimatedBlocks() : 1L;
                return (int) Math.max(1L, bR + bS);
            }
            case EXTERNAL_MERGE_SORT -> {
                long bR = left != null ? left.getEstimatedBlocks() : 1L;
                return (int) Math.max(1L, 2 * bR);
            }
            default -> {
                return calculateDefaultNodeCost(node);
            }
        }
    }

    private int calculateDefaultNodeCost(QueryTree node) {
        return switch (node.getNodeType()) {
            case INSERT, UPDATE, DELETE -> 1;
            case CREATE_INDEX -> {
                Statistic stat = getTableStatistics(node.getTableName());
                int br = stat != null ? stat.br() : 1;
                yield br + Math.max(1, br / 10);
            }
            default -> 0; // In-memory operations: PROJECT, SELECT filter streaming, etc.
        };
    }

    private PhysicalAlgorithm inferDefaultAlgorithm(QueryTree node) {
        return switch (node.getNodeType()) {
            case TABLE_SCAN -> PhysicalAlgorithm.LINEAR_SCAN;
            case JOIN, CARTESIAN -> PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN;
            case SORT -> PhysicalAlgorithm.EXTERNAL_MERGE_SORT;
            default -> PhysicalAlgorithm.DEFAULT;
        };
    }

    // isi tabel mentah dari statistik, belum disaring
    private PlanEstimate tableEstimate(QueryTree node) {
        Statistic stat = getTableStatistics(node.getTableName());
        if (stat != null) {
            Map<String, Long> dv = new LinkedHashMap<>();
            if (stat.distinctValues() != null) {
                for (Map.Entry<String, Integer> e : stat.distinctValues().entrySet()) {
                    dv.put(e.getKey(), e.getValue().longValue());
                }
            }
            long rows = stat.nr();
            long blocks = stat.br();
            if (blocks == 0 && rows > 0) {
                int fr = stat.fr() > 0 ? stat.fr() : 10;
                blocks = Math.max(1L, (long) Math.ceil((double) rows / fr));
            }
            return new PlanEstimate(rows, blocks, dv);
        }
        return new PlanEstimate(100L, 10L, Map.of());
    }

    @Override
    public PlanEstimate estimateOutput(QueryTree node) {
        if (node == null) {
            return new PlanEstimate(0L, 0L, Map.of());
        }

        switch (node.getNodeType()) {
            case TABLE_SCAN -> {
                // scan lewat index cuma ngeluarin baris yang lolos kondisinya
                PlanEstimate current = tableEstimate(node);
                for (ExpressionTree cond : node.getConditions()) {
                    current = cardinalityEstimator.estimateSelection(current, cond);
                }
                return current;
            }
            case SELECT -> {
                List<QueryTree> children = node.getChildren();
                PlanEstimate childEst = children.isEmpty() ? new PlanEstimate(0L, 0L, Map.of()) : estimateOutput(children.get(0));
                if (node.getConditions().isEmpty()) {
                    return childEst;
                }
                PlanEstimate current = childEst;
                for (ExpressionTree cond : node.getConditions()) {
                    current = cardinalityEstimator.estimateSelection(current, cond);
                }
                return current;
            }
            case CARTESIAN -> {
                List<QueryTree> children = node.getChildren();
                if (children.size() < 2) {
                    return children.isEmpty() ? new PlanEstimate(0L, 0L, Map.of()) : estimateOutput(children.get(0));
                }
                PlanEstimate left = estimateOutput(children.get(0));
                PlanEstimate right = estimateOutput(children.get(1));
                return cardinalityEstimator.estimateCartesianProduct(left, right);
            }
            case JOIN -> {
                List<QueryTree> children = node.getChildren();
                if (children.size() < 2) {
                    return children.isEmpty() ? new PlanEstimate(0L, 0L, Map.of()) : estimateOutput(children.get(0));
                }
                PlanEstimate left = estimateOutput(children.get(0));
                PlanEstimate right = estimateOutput(children.get(1));
                ExpressionTree cond = !node.getConditions().isEmpty() ? node.getConditions().get(0) : null;
                return cardinalityEstimator.estimateJoin(left, right, cond);
            }
            case PROJECT, SORT -> {
                List<QueryTree> children = node.getChildren();
                return children.isEmpty() ? new PlanEstimate(0L, 0L, Map.of()) : estimateOutput(children.get(0));
            }
            case LIMIT -> {
                List<QueryTree> children = node.getChildren();
                PlanEstimate childEst = children.isEmpty() ? new PlanEstimate(0L, 0L, Map.of()) : estimateOutput(children.get(0));
                if (node.getLimit() == null) {
                    return childEst;
                }
                long limitedRows = Math.min(childEst.getEstimatedRows(), node.getLimit().longValue());
                long limitedBlocks = limitedRows == 0 ? 0L : Math.min(
                    childEst.getEstimatedBlocks(),
                    Math.max(1L, Math.round((double) childEst.getEstimatedBlocks() * limitedRows / Math.max(1L, childEst.getEstimatedRows())))
                );
                Map<String, Long> dv = new LinkedHashMap<>();
                for (Map.Entry<String, Long> e : childEst.getAllDistinctValues().entrySet()) {
                    dv.put(e.getKey(), Math.min(e.getValue(), limitedRows));
                }
                return new PlanEstimate(limitedRows, limitedBlocks, dv);
            }
            case INSERT, UPDATE, DELETE -> {
                String tableName = node.getTableName();
                Statistic stat = getTableStatistics(tableName);
                if (stat != null) {
                    return new PlanEstimate(stat.nr(), stat.br(), Map.of());
                }
                return new PlanEstimate(1L, 1L, Map.of());
            }
            default -> {
                if (!node.getChildren().isEmpty()) {
                    return estimateOutput(node.getChildren().get(0));
                }
                return new PlanEstimate(1L, 1L, Map.of());
            }
        }
    }

    private Statistic getTableStatistics(String tableName) {
        if (tableName == null || tableName.isBlank() || statisticsProvider == null) {
            return null;
        }
        return statisticsProvider.getStatistics(tableName);
    }
}
