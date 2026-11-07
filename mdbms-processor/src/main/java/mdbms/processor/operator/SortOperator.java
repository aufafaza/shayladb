package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import mdbms.processor.IResultRow;

public class SortOperator extends QueryUnaryOperator {
    public record SortKey(String tableName, String columnName, boolean ascending) {
        public SortKey {
            Objects.requireNonNull(columnName, "Nama kolom wajib diisi");
            if (columnName.isBlank()) throw new IllegalArgumentException("Nama kolom kosong");
        }
    }

    private final List<SortKey> sortKeys;
    private final List<IResultRow> rows = new ArrayList<>();
    private int position;

    public SortOperator(IQueryOperator child, String column, boolean ascending) {
        this(child, List.of(new SortKey(null, column, ascending)));
    }

    public SortOperator(IQueryOperator child, List<SortKey> sortKeys) {
        super(child);
        Objects.requireNonNull(sortKeys, "Kolom pengurutan wajib diisi");
        if (sortKeys.isEmpty()) throw new IllegalArgumentException("Kolom pengurutan kosong");
        this.sortKeys = List.copyOf(sortKeys);
    }

    @Override
    public void open() {
        rows.clear();
        position = 0;
        childOperator.open();
        IResultRow row;
        while ((row = childOperator.next()) != null) rows.add(row);
        rows.sort(this::compareRows);
    }

    private int compareRows(IResultRow left, IResultRow right) {
        for (SortKey key : sortKeys) {
            Object a = key.tableName() == null ? left.get(key.columnName())
                    : left.get(key.tableName(), key.columnName());
            Object b = key.tableName() == null ? right.get(key.columnName())
                    : right.get(key.tableName(), key.columnName());
            int result = compare(a, b);
            if (result != 0) return key.ascending() ? result : -result;
        }
        return 0;
    }

    @Override
    public IResultRow next() {
        return position < rows.size() ? rows.get(position++) : null;
    }

    @Override
    public void close() {
        try { childOperator.close(); }
        finally { rows.clear(); position = 0; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        throw new IllegalArgumentException("Tipe kolom tidak dapat dibandingkan");
    }
}
