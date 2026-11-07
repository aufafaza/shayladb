package mdbms.optimizer.binder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;

public class BindingContext {
    private final Map<String, TableBinding> tableBindings = new LinkedHashMap<>();
    private final Map<ColumnReference, ColumnBinding> columnBindings = new LinkedHashMap<>();

    public void registerTableBinding(String alias, TableBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        String key = (alias != null && !alias.isBlank()) ? alias : binding.getTableName();
        tableBindings.put(key, binding);
        if (!tableBindings.containsKey(binding.getTableName())) {
            tableBindings.put(binding.getTableName(), binding);
        }
    }

    public void registerColumnBinding(ColumnReference reference, ColumnBinding binding) {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        columnBindings.put(reference, binding);
    }

    public TableBinding getTableBinding(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        TableBinding direct = tableBindings.get(alias);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, TableBinding> entry : tableBindings.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(alias)) {
                return entry.getValue();
            }
            TableBinding tb = entry.getValue();
            if (tb.getAlias().equalsIgnoreCase(alias) || tb.getTableName().equalsIgnoreCase(alias)) {
                return tb;
            }
        }
        return null;
    }

    public ColumnBinding getColumnBinding(ColumnReference reference) {
        if (reference == null) {
            return null;
        }
        ColumnBinding direct = columnBindings.get(reference);
        if (direct != null) {
            return direct;
        }
        List<ColumnBinding> matches = new ArrayList<>();
        for (Map.Entry<ColumnReference, ColumnBinding> entry : columnBindings.entrySet()) {
            ColumnReference key = entry.getKey();
            if (key.getColumnName().equalsIgnoreCase(reference.getColumnName())) {
                if (reference.getTableName() == null) {
                    if (key.getTableName() == null) {
                        return entry.getValue();
                    }
                    if (!matches.contains(entry.getValue())) {
                        matches.add(entry.getValue());
                    }
                } else if (key.getTableName() != null && key.getTableName().equalsIgnoreCase(reference.getTableName())) {
                    return entry.getValue();
                } else if (entry.getValue().getTableAlias().equalsIgnoreCase(reference.getTableName())
                    || entry.getValue().getTableName().equalsIgnoreCase(reference.getTableName())) {
                    return entry.getValue();
                }
            }
        }
        if (reference.getTableName() == null && matches.size() == 1) {
            return matches.get(0);
        }
        return null;
    }

    public Set<String> getReferencedTables(ExpressionTree expression) {
        if (expression == null || expression.getRoot() == null) {
            return Collections.emptySet();
        }
        Set<String> tables = new LinkedHashSet<>();
        collectReferencedTables(expression.getRoot(), tables);
        return Collections.unmodifiableSet(tables);
    }

    private void collectReferencedTables(ExpressionNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        if (node.getType() == ExpressionNode.Type.COLUMN_REFERENCE) {
            String colName = node.getValue();
            String tblName = node.getTableName();
            if (tblName != null && !tblName.isBlank()) {
                TableBinding tb = getTableBinding(tblName);
                if (tb != null) {
                    tables.add(tb.getAlias());
                } else {
                    tables.add(tblName);
                }
            } else {
                ColumnBinding cb = getColumnBinding(new ColumnReference(null, colName));
                if (cb != null) {
                    tables.add(cb.getTableAlias());
                }
            }
        }
        collectReferencedTables(node.getLeft(), tables);
        collectReferencedTables(node.getRight(), tables);
    }

    public List<ColumnBinding> getVisibleColumns(Set<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Collections.emptyList();
        }
        List<ColumnBinding> visible = new ArrayList<>();
        for (String alias : aliases) {
            TableBinding tb = getTableBinding(alias);
            if (tb != null && tb.getSchema() != null && tb.getSchema().columns() != null) {
                for (ColumnDefinition col : tb.getSchema().columns()) {
                    visible.add(new ColumnBinding(tb.getTableName(), tb.getAlias(), col));
                }
            }
        }
        return Collections.unmodifiableList(visible);
    }

    public Map<String, TableBinding> getTableBindings() {
        return Collections.unmodifiableMap(tableBindings);
    }

    public Map<ColumnReference, ColumnBinding> getColumnBindings() {
        return Collections.unmodifiableMap(columnBindings);
    }
}
