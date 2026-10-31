package mdbms.optimizer;

import mdbms.models.QueryTree;

public interface IOptimizationRule {
    String getRuleName();

    boolean matches(QueryTree node);

    QueryTree apply(QueryTree node, OptimizationContext context);
}
