
package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mdbms.processor.ResultRow;

public class SortOperator implements IQueryOperator {

    private final IQueryOperator childOperator;
    private final String sortColumn;
    private final boolean ascending;
    private final List<ResultRow> sortedRows = new ArrayList<>();

    private int currentIndex = 0;

    public SortOperator(IQueryOperator childOperator,
                        String sortColumn,
                        boolean ascending) {
        this.childOperator = childOperator;
        this.sortColumn = sortColumn;
        this.ascending = ascending;
    }

    @Override
    public void open() {
        sortedRows.clear();
        currentIndex = 0;

        childOperator.open();

        ResultRow row;
        while ((row = childOperator.next()) != null) {
            sortedRows.add(row);
        }

        Comparator<ResultRow> comparator =
                (row1, row2) -> compareValues(
                        row1.getValue(sortColumn),
                        row2.getValue(sortColumn)
                );

        if (!ascending) {
            comparator = comparator.reversed();
        }

        sortedRows.sort(comparator);
    }

    @Override
    public ResultRow next() {
        if (currentIndex >= sortedRows.size()) {
            return null;
        }

        return sortedRows.get(currentIndex++);
    }

    @Override
    public void close() {
        childOperator.close();
        sortedRows.clear();
        currentIndex = 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareValues(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;

        if (left instanceof Number && right instanceof Number) {
            return Double.compare(
                    ((Number) left).doubleValue(),
                    ((Number) right).doubleValue()
            );
        }

        if (left instanceof Comparable
                && left.getClass().isInstance(right)) {
            return ((Comparable) left).compareTo(right);
        }

        return left.toString().compareTo(right.toString());
    }
}
