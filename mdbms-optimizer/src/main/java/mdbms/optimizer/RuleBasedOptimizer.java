package mdbms.optimizer;

import java.util.List;
import mdbms.models.QueryTree;

public class RuleBasedOptimizer {
    private final List<IOptimizationRule> rules;

    public RuleBasedOptimizer(List<IOptimizationRule> rules) {
        this.rules = rules;
    }

    public QueryTree optimize(QueryTree root, OptimizationContext context) {
        // TODO
        return null;
    }

    public void addRule(IOptimizationRule rule) {
        // TODO
    }

    public List<IOptimizationRule> getRules() {
        return rules;
    }
}
