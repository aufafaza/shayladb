package mdbms.optimizer;

import mdbms.models.PhysicalAlgorithm;
import mdbms.models.QueryTree;

public interface ICostEstimator {
    int calculateTotalCost(QueryTree root);

    int calculateNodeCost(QueryTree node, PhysicalAlgorithm algorithm, PlanEstimate left, PlanEstimate right);

    PlanEstimate estimateOutput(QueryTree node);
}
