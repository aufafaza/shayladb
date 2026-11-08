package mdbms.optimizer.rule;

import mdbms.models.query.QueryTree;

public interface IOptimizationRule {
    String getRuleName();

    boolean matches(QueryTree node);

    QueryTree apply(QueryTree node, OptimizationContext context);
}
