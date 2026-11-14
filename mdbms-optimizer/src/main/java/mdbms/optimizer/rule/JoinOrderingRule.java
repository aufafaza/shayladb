package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.cost.ICostEstimator;
import mdbms.optimizer.cost.SystemCostEstimator;
import mdbms.optimizer.cost.CardinalityEstimator;

public class JoinOrderingRule implements IOptimizationRule {
    private final JoinConversionRule conversion = new JoinConversionRule();

    @Override
    public String getRuleName() {
        return "JoinOrdering";
    }

    @Override
    public boolean matches(QueryTree node) {
        return conversion.matches(node);
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node) || context == null) {
            return node;
        }
        BindingContext bindings = context.getBindingContext();
        ICostEstimator estimator = context.getCostEstimator();
        if (estimator == null) {
            estimator = new SystemCostEstimator(context::getStatistics, new CardinalityEstimator());
        }
        List<QueryTree> inputs = new ArrayList<>();
        List<ExpressionTree> remaining = new ArrayList<>();
        conversion.collect(node, inputs, remaining);
        List<Set<String>> tables = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QueryTree input : inputs) {
            Set<String> names = JoinConversionRule.tablesIn(input, bindings);
            // keep natural joins and other multi-relation boundaries opaque, and
            // do not reorder a self join without distinct relation identities.
            if (names.size() != 1 || !Collections.disjoint(seen, names)) {
                return node;
            }
            tables.add(names);
            seen.addAll(names);
        }

        // Include local filters in the starting-table estimates, including those
        // originally written in ON rather than WHERE.
        for (int i = 0; i < inputs.size(); i++) {
            List<ExpressionTree> local = new ArrayList<>();
            for (ExpressionTree predicate : remaining) {
                Set<String> refs = JoinConversionRule.references(predicate, bindings);
                if (refs != null && !refs.isEmpty() && tables.get(i).containsAll(refs)) {
                    local.add(predicate);
                }
            }
            inputs.set(i, selection(inputs.get(i), local));
            remaining.removeAll(local);
        }

        int first = 0;
        long smallest = Long.MAX_VALUE;
        for (int i = 0; i < inputs.size(); i++) {
            long rows = estimator.estimateOutput(inputs.get(i)).getEstimatedRows();
            if (rows < smallest) {
                first = i;
                smallest = rows;
            }
        }
        QueryTree result = inputs.remove(first);
        Set<String> available = new HashSet<>(tables.remove(first));
        while (!inputs.isEmpty()) {
            int best = -1;
            long bestRows = Long.MAX_VALUE;
            boolean bestConnected = false;
            QueryTree bestPlan = null;
            List<ExpressionTree> bestConditions = List.of();
            for (int i = 0; i < inputs.size(); i++) {
                Set<String> combined = new HashSet<>(available);
                combined.addAll(tables.get(i));
                List<ExpressionTree> conditions = new ArrayList<>();
                for (ExpressionTree predicate : remaining) {
                    Set<String> refs = JoinConversionRule.references(predicate, bindings);
                    if (refs != null && combined.containsAll(refs)
                        && !Collections.disjoint(refs, available)
                        && !Collections.disjoint(refs, tables.get(i))) {
                        conditions.add(predicate);
                    }
                }
                boolean connected = !conditions.isEmpty();
                QueryTree candidate = new QueryTree(connected ? QueryNodeType.JOIN : QueryNodeType.CARTESIAN,
                    PhysicalAlgorithm.DEFAULT, null, conditions, List.of(result, inputs.get(i)), 0);
                long rows = estimator.estimateOutput(candidate).getEstimatedRows();
                if (best == -1 || (connected && !bestConnected)
                    || (connected == bestConnected && rows < bestRows)) {
                    best = i;
                    bestRows = rows;
                    bestConnected = connected;
                    bestPlan = candidate;
                    bestConditions = conditions;
                }
            }
            result = bestPlan;
            available.addAll(tables.remove(best));
            inputs.remove(best);
            remaining.removeAll(bestConditions);
        }
        return selection(result, remaining);
    }

    private static QueryTree selection(QueryTree input, List<ExpressionTree> conditions) {
        return conditions.isEmpty() ? input : new QueryTree(QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT, null, conditions, List.of(input), 0);
    }
}
