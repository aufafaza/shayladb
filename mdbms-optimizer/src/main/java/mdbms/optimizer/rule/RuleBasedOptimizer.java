package mdbms.optimizer.rule;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import mdbms.models.query.QueryTree;

public class RuleBasedOptimizer {
    private final List<IOptimizationRule> rules;

    public RuleBasedOptimizer(List<IOptimizationRule> rules) {
        this.rules = new ArrayList<>(rules);
    }

    public QueryTree optimize(QueryTree root, OptimizationContext context) {
        Objects.requireNonNull(root, "root");
        QueryTree result = root;
        for (IOptimizationRule rule : rules) {
            result = applyRecursively(result, rule, context);
        }
        return result;
    }

    private QueryTree applyRecursively(QueryTree node, IOptimizationRule rule, OptimizationContext context) {
        List<QueryTree> children = node.getChildren().stream()
            .map(child -> applyRecursively(child, rule, context)).toList();
        QueryTree result = children.equals(node.getChildren()) ? node : QueryTreeRewrites.withChildren(node, children);
        return rule.matches(result) ? rule.apply(result, context) : result;
    }

    public void addRule(IOptimizationRule rule) {
        rules.add(Objects.requireNonNull(rule, "rule"));
    }

    public List<IOptimizationRule> getRules() {
        return List.copyOf(rules);
    }
}
