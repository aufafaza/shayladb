package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.TableBinding;

// QO-09: optimasi seleksi
public class SelectionPushDownRule implements IOptimizationRule {
    @Override
    public String getRuleName() {
        return "SelectionPushDown";
    }

    @Override
    public boolean matches(QueryTree node) {
        return node != null && node.getNodeType() == QueryNodeType.SELECT;
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node)) {
            return node;
        }
        // kumpulin kondisi dari SELECT yang bertumpuk
        List<ExpressionTree> conjuncts = new ArrayList<>();
        QueryTree source = node;
        while (source.getNodeType() == QueryNodeType.SELECT && source.getChildren().size() == 1) {
            for (ExpressionTree condition : source.getConditions()) {
                conjuncts.addAll(QueryTreeRewrites.splitConjuncts(condition));
            }
            source = source.getChildren().get(0);
        }

        BindingContext bindings = context == null ? null : context.getBindingContext();
        QueryTree result = source;
        List<ExpressionTree> stayOnTop = new ArrayList<>();
        for (ExpressionTree condition : conjuncts) {
            Set<String> tables = referencedTables(condition, bindings);
            if (tables.isEmpty()) {
                stayOnTop.add(condition);
            } else {
                result = pushDown(result, condition, tables, bindings);
            }
        }
        // kondisi tanpa tabel jelas tetap di atas, urutan asli
        for (int i = stayOnTop.size() - 1; i >= 0; i--) {
            result = QueryTreeRewrites.selection(stayOnTop.get(i), result);
        }
        return result;
    }

    private QueryTree pushDown(QueryTree tree, ExpressionTree condition, Set<String> tables,
                               BindingContext bindings) {
        QueryNodeType type = tree.getNodeType();
        if (type == QueryNodeType.SELECT && tree.getChildren().size() == 1) {
            QueryTree child = pushDown(tree.getChildren().get(0), condition, tables, bindings);
            return QueryTreeRewrites.withChildren(tree, List.of(child));
        }
        if ((type == QueryNodeType.CARTESIAN || type == QueryNodeType.JOIN) && tree.getChildren().size() == 2) {
            QueryTree left = tree.getChildren().get(0);
            QueryTree right = tree.getChildren().get(1);
            if (tablesUnder(left, bindings).containsAll(tables)) {
                return QueryTreeRewrites.withChildren(tree,
                    List.of(pushDown(left, condition, tables, bindings), right));
            }
            if (tablesUnder(right, bindings).containsAll(tables)) {
                return QueryTreeRewrites.withChildren(tree,
                    List.of(left, pushDown(right, condition, tables, bindings)));
            }
        }
        // mentok di sini: tepat di atas tabel atau join yang mempertemukan kedua sisi
        return QueryTreeRewrites.selection(condition, tree);
    }

    private static Set<String> referencedTables(ExpressionTree condition, BindingContext bindings) {
        Set<String> result = new HashSet<>();
        if (bindings != null) {
            for (String table : bindings.getReferencedTables(condition)) {
                result.add(table.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static Set<String> tablesUnder(QueryTree node, BindingContext bindings) {
        Set<String> result = new HashSet<>();
        collectTables(node, bindings, result);
        return result;
    }

    private static void collectTables(QueryTree node, BindingContext bindings, Set<String> out) {
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN && node.getTableName() != null) {
            TableBinding binding = bindings.getTableBinding(node.getTableName());
            String name = binding != null ? binding.getAlias() : node.getTableName();
            out.add(name.toLowerCase(Locale.ROOT));
        }
        for (QueryTree child : node.getChildren()) {
            collectTables(child, bindings, out);
        }
    }
}
