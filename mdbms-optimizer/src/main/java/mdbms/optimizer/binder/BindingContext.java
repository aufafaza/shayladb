package mdbms.optimizer.binder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionTree;

public class BindingContext {
    private Map<String, TableBinding> tableBindings;
    private Map<ColumnReference, ColumnBinding> columnBindings;

    public void registerTableBinding(String alias, TableBinding binding) {
        // TODO
    }

    public void registerColumnBinding(ColumnReference reference, ColumnBinding binding) {
        // TODO
    }

    public TableBinding getTableBinding(String alias) {
        // TODO
        return null;
    }

    public ColumnBinding getColumnBinding(ColumnReference reference) {
        // TODO
        return null;
    }

    public Set<String> getReferencedTables(ExpressionTree expression) {
        // TODO
        return Set.of();
    }

    public List<ColumnBinding> getVisibleColumns(Set<String> aliases) {
        // TODO
        return List.of();
    }
}
