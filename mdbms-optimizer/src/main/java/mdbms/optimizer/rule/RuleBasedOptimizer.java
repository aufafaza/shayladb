package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import mdbms.models.query.QueryTree;

public class RuleBasedOptimizer {
    // jaga-jaga biar ga muter terus kalau ada rule yang bolak-balik
    static final int MAX_PASSES = 10;

    private final List<IOptimizationRule> rules;

    public RuleBasedOptimizer(List<IOptimizationRule> rules) {
        this.rules = new ArrayList<>(rules == null ? List.of() : rules);
    }

    // urutan default: seleksi turun dulu, index dan proyeksi belakangan
    public static List<IOptimizationRule> defaultRules() {
        return List.of(
            new NaturalJoinNormalizationRule(),
            new SelectionPushDownRule(),
            new JoinConversionRule(),
            new JoinOrderingRule(),
            new IndexSelectionRule(),
            new JoinAlgorithmSelectionRule(),
            new ProjectionPushDownRule()
        );
    }

    public QueryTree optimize(QueryTree root, OptimizationContext context) {
        if (root == null) {
            return null;
        }
        QueryTree current = root;
        String before = current.formatTree();
        // ulang sampai pohonnya ga berubah lagi
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            QueryTree next = applyBottomUp(current, context);
            String after = next.formatTree();
            current = next;
            if (after.equals(before)) {
                break;
            }
            before = after;
        }
        return current;
    }

    private QueryTree applyBottomUp(QueryTree node, OptimizationContext context) {
        QueryTree current = node;
        if (!node.getChildren().isEmpty()) {
            List<QueryTree> children = new ArrayList<>();
            boolean changed = false;
            for (QueryTree child : node.getChildren()) {
                QueryTree optimized = applyBottomUp(child, context);
                changed |= optimized != child;
                children.add(optimized);
            }
            if (changed) {
                current = QueryTreeRewrites.withChildren(node, children);
            }
        }
        for (IOptimizationRule rule : rules) {
            if (rule.matches(current)) {
                QueryTree result = rule.apply(current, context);
                // rule yang belum jadi bisa balikin null, abaikan
                if (result != null) {
                    current = result;
                }
            }
        }
        return current;
    }

    public void addRule(IOptimizationRule rule) {
        rules.add(Objects.requireNonNull(rule, "rule"));
    }

    public List<IOptimizationRule> getRules() {
        return Collections.unmodifiableList(rules);
    }
}
