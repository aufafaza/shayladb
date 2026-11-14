package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.ColumnBinding;
import mdbms.optimizer.binder.TableBinding;

// QO-10: optimasi proyeksi
public class ProjectionPushDownRule implements IOptimizationRule {
    @Override
    public String getRuleName() {
        return "ProjectionPushDown";
    }

    @Override
    public boolean matches(QueryTree node) {
        return node != null && node.getNodeType() == QueryNodeType.PROJECT;
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node) || node.getChildren().size() != 1) {
            return node;
        }
        QueryTree project = collapseChain(node);
        BindingContext bindings = context == null ? null : context.getBindingContext();
        Set<ColumnReference> required = resolvedColumnsUsedBy(project, bindings);
        if (required == null) {
            return project;
        }
        QueryTree child = prune(project.getChildren().get(0), required, bindings);
        // ada kolom yang ga ke-resolve, jangan ambil risiko
        return child == null ? project : QueryTreeRewrites.withChildren(project, List.of(child));
    }

    // turunin daftar kolom yang dibutuhin, pasang PROJECT di atas tiap sisi join
    private QueryTree prune(QueryTree node, Set<ColumnReference> required, BindingContext bindings) {
        List<QueryTree> children = node.getChildren();
        if (node.getNodeType() == QueryNodeType.PROJECT && children.size() == 1) {
            return prune(children.get(0), required, bindings);
        }
        if (children.isEmpty()) {
            return node;
        }
        Set<ColumnReference> needed = resolvedColumnsUsedBy(node, bindings);
        if (needed == null) {
            return null;
        }
        needed.addAll(required);

        boolean isJoin = node.getNodeType() == QueryNodeType.JOIN || node.getNodeType() == QueryNodeType.CARTESIAN;
        List<QueryTree> newChildren = new ArrayList<>();
        for (QueryTree child : children) {
            QueryTree pruned = isJoin
                ? pruneJoinSide(child, needed, bindings)
                : prune(child, needed, bindings);
            if (pruned == null) {
                return null;
            }
            newChildren.add(pruned);
        }
        return QueryTreeRewrites.withChildren(node, newChildren);
    }

    private QueryTree pruneJoinSide(QueryTree side, Set<ColumnReference> needed, BindingContext bindings) {
        List<ColumnReference> available = columnsOf(side, bindings);
        List<ColumnReference> kept = new ArrayList<>();
        for (ColumnReference column : available) {
            if (needed.contains(column)) {
                kept.add(column);
            }
        }
        QueryTree pruned = prune(side, new LinkedHashSet<>(kept), bindings);
        // kosong atau semua kolom kepakai, PROJECT ga ada gunanya
        if (pruned == null || kept.isEmpty() || kept.size() == available.size()) {
            return pruned;
        }
        return new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(pruned), 0, kept, false);
    }

    // semua kolom tabel di bawah node, urut sesuai skema
    private static List<ColumnReference> columnsOf(QueryTree node, BindingContext bindings) {
        List<ColumnReference> result = new ArrayList<>();
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN && node.getTableName() != null) {
            TableBinding table = bindings.getTableBinding(node.getTableName());
            if (table != null) {
                for (ColumnDefinition column : table.getSchema().columns()) {
                    result.add(new ColumnReference(table.getAlias(), column.name()));
                }
            }
        }
        for (QueryTree child : node.getChildren()) {
            result.addAll(columnsOf(child, bindings));
        }
        return result;
    }

    // ubah kolom jadi bentuk tabel.kolom, null kalau ga ketemu
    static ColumnReference resolve(ColumnReference column, BindingContext bindings) {
        if (bindings == null || column == null) {
            return null;
        }
        ColumnBinding binding = bindings.getColumnBinding(column);
        return binding == null ? null : new ColumnReference(binding.getTableAlias(), binding.getColumnName());
    }

    // kolom yang dipakai node, sudah di-resolve; null kalau ada yang ga ketemu
    static Set<ColumnReference> resolvedColumnsUsedBy(QueryTree node, BindingContext bindings) {
        Set<ColumnReference> result = new LinkedHashSet<>();
        for (ColumnReference column : QueryTreeRewrites.columnsUsedBy(node)) {
            ColumnReference resolved = resolve(column, bindings);
            if (resolved == null) {
                return null;
            }
            result.add(resolved);
        }
        return result;
    }

    // PROJECT berantai cukup diwakili yang paling luar
    private static QueryTree collapseChain(QueryTree project) {
        QueryTree child = project.getChildren().get(0);
        while (child.getNodeType() == QueryNodeType.PROJECT && child.getChildren().size() == 1) {
            child = child.getChildren().get(0);
        }
        return child == project.getChildren().get(0)
            ? project
            : QueryTreeRewrites.withChildren(project, List.of(child));
    }
}
