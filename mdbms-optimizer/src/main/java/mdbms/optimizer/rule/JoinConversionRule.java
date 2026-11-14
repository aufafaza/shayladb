package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.ColumnBinding;
import mdbms.optimizer.binder.TableBinding;

/** Converts inner-join regions to left-deep theta joins without reordering tables. */
public class JoinConversionRule implements IOptimizationRule {
    @Override
    public String getRuleName() {
        return "JoinConversion";
    }

    @Override
    public boolean matches(QueryTree node) {
        if (node == null) {
            return false;
        }
        while (node.getNodeType() == QueryNodeType.SELECT && node.getChildren().size() == 1) {
            node = node.getChildren().get(0);
        }
        return isInnerJoin(node);
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node)) {
            return node;
        }
        List<QueryTree> inputs = new ArrayList<>();
        List<ExpressionTree> predicates = new ArrayList<>();
        collect(node, inputs, predicates);
        BindingContext bindings = context == null ? null : context.getBindingContext();

        List<Set<String>> inputTables = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QueryTree input : inputs) {
            Set<String> tables = tablesIn(input, bindings);
            if (tables.isEmpty() || !java.util.Collections.disjoint(seen, tables)) {
                return node;
            }
            seen.addAll(tables);
            inputTables.add(tables);
        }

        QueryTree result = inputs.get(0);
        Set<String> available = new HashSet<>(inputTables.get(0));
        List<ExpressionTree> remaining = new ArrayList<>(predicates);
        for (int i = 1; i < inputs.size(); i++) {
            Set<String> right = inputTables.get(i);
            Set<String> combined = new HashSet<>(available);
            combined.addAll(right);
            List<ExpressionTree> joinConditions = new ArrayList<>();
            for (ExpressionTree predicate : remaining) {
                Set<String> references = references(predicate, bindings);
                if (references != null && combined.containsAll(references)
                    && !java.util.Collections.disjoint(references, available)
                    && !java.util.Collections.disjoint(references, right)) {
                    joinConditions.add(predicate);
                }
            }
            remaining.removeAll(joinConditions);
            QueryNodeType type = joinConditions.isEmpty() ? QueryNodeType.CARTESIAN : QueryNodeType.JOIN;
            result = new QueryTree(type, PhysicalAlgorithm.DEFAULT, null,
                joinConditions, List.of(result, inputs.get(i)), 0);
            available = combined;
        }
        if (!remaining.isEmpty()) {
            result = new QueryTree(QueryNodeType.SELECT, PhysicalAlgorithm.DEFAULT, null,
                remaining, List.of(result), 0);
        }
        return result;
    }

    private static boolean isInnerJoin(QueryTree node) {
        return node.getChildren().size() == 2
            && (node.getNodeType() == QueryNodeType.CARTESIAN
                || (node.getNodeType() == QueryNodeType.JOIN && !node.isNaturalJoin()));
    }

    void collect(QueryTree node, List<QueryTree> inputs, List<ExpressionTree> predicates) {
        if (matches(node)) {
            for (ExpressionTree condition : node.getConditions()) {
                if (condition.getRoot().getType() == ExpressionNode.Type.LOGICAL_AND) {
                    predicates.addAll(QueryTreeRewrites.splitConjuncts(condition));
                } else {
                    predicates.add(condition);
                }
            }
            for (QueryTree child : node.getChildren()) {
                collect(child, inputs, predicates);
            }
        } else {
            inputs.add(node);
        }
    }

    static Set<String> tablesIn(QueryTree node, BindingContext bindings) {
        Set<String> result = new HashSet<>();
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN) {
            result.add(tableKey(node.getTableName(), bindings));
        }
        for (QueryTree child : node.getChildren()) {
            result.addAll(tablesIn(child, bindings));
        }
        return result;
    }

    static Set<String> references(ExpressionTree predicate, BindingContext bindings) {
        Set<String> result = new HashSet<>();
        for (ColumnReference column : QueryTreeRewrites.columnsIn(predicate)) {
            String table = column.getTableName();
            if (table == null) {
                ColumnBinding binding = bindings == null ? null : bindings.getColumnBinding(column);
                if (binding == null) {
                    return null;
                }
                table = binding.getTableAlias();
            }
            result.add(tableKey(table, bindings));
        }
        return result;
    }

    private static String tableKey(String name, BindingContext bindings) {
        TableBinding binding = bindings == null ? null : bindings.getTableBinding(name);
        return (binding == null ? name : binding.getAlias()).toLowerCase(Locale.ROOT);
    }
}
