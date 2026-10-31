package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mdbms.processor.IResultRow;

public class SortOperator extends QueryUnaryOperator {
    private final String column;
    private final boolean ascending;
    private final List<IResultRow> rows = new ArrayList<>();
    private int position;

    public SortOperator(IQueryOperator child, String column, boolean ascending) {
        super(child);
        this.column = column;
        this.ascending = ascending;
    }

    @Override
    public void open() {
        rows.clear();
        position = 0;
        childOperator.open();
        IResultRow row;
        while ((row = childOperator.next()) != null) rows.add(row);
        Comparator<IResultRow> comparator = (a, b) -> compare(a.get(column), b.get(column));
        rows.sort(ascending ? comparator : comparator.reversed());
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
