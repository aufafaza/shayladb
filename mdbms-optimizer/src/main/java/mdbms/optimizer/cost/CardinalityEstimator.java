package mdbms.optimizer.cost;

import mdbms.models.query.ExpressionTree;

public class CardinalityEstimator {
    public PlanEstimate estimateSelection(PlanEstimate input, ExpressionTree predicate) {
        // TODO
        return null;
    }

    public PlanEstimate estimateCartesianProduct(PlanEstimate left, PlanEstimate right) {
        // TODO
        return null;
    }

    public PlanEstimate estimateJoin(PlanEstimate left, PlanEstimate right, ExpressionTree predicate) {
        // TODO
        return null;
    }
}
