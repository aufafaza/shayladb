package mdbms.optimizer;

import mdbms.api.IStatisticsProvider;
import mdbms.models.PhysicalAlgorithm;
import mdbms.models.QueryTree;

public class SystemCostEstimator implements ICostEstimator {
    private final IStatisticsProvider statisticsProvider;
    private final CardinalityEstimator cardinalityEstimator;

    public SystemCostEstimator(IStatisticsProvider statisticsProvider, CardinalityEstimator cardinalityEstimator) {
        this.statisticsProvider = statisticsProvider;
        this.cardinalityEstimator = cardinalityEstimator;
    }

    @Override
    public int calculateTotalCost(QueryTree root) {
        // TODO
        return 0;
    }

    @Override
    public int calculateNodeCost(QueryTree node, PhysicalAlgorithm algorithm, PlanEstimate left, PlanEstimate right) {
        // TODO
        return 0;
    }

    @Override
    public PlanEstimate estimateOutput(QueryTree node) {
        // TODO
        return null;
    }
}
