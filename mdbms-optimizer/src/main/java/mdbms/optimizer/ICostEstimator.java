package mdbms.optimizer;

import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryTree;

public interface ICostEstimator {
    int calculateTotalCost(QueryTree root);

    int calculateNodeCost(QueryTree node, PhysicalAlgorithm algorithm, PlanEstimate left, PlanEstimate right);

    PlanEstimate estimateOutput(QueryTree node);
}
