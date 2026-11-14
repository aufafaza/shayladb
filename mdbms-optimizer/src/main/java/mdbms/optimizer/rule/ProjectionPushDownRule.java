package mdbms.optimizer.rule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.ColumnBinding;

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
        return collapseChain(node);
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
