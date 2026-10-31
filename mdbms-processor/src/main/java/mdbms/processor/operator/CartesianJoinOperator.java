package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import mdbms.processor.IResultRow;

public class CartesianJoinOperator extends QueryBinaryOperator {
    private final List<IResultRow> rightRows = new ArrayList<>();
    private IResultRow currentLeft;
    private int rightIndex;

    public CartesianJoinOperator(IQueryOperator left, IQueryOperator right) {
        super(left, right);
    }

    @Override
    public void open() {
        rightRows.clear();
        rightIndex = 0;
        leftOperator.open();
        rightOperator.open();
        IResultRow row;
        while ((row = rightOperator.next()) != null) rightRows.add(row);
        currentLeft = leftOperator.next();
    }

    @Override
    public IResultRow next() {
        while (currentLeft != null && !rightRows.isEmpty()) {
            if (rightIndex < rightRows.size()) {
                return JoinRowUtil.combine(currentLeft, rightRows.get(rightIndex++), false);
            }
            currentLeft = leftOperator.next();
            rightIndex = 0;
        }
        return null;
    }

    @Override
    public void close() {
        try { leftOperator.close(); }
        finally {
            try { rightOperator.close(); }
            finally { rightRows.clear(); currentLeft = null; rightIndex = 0; }
        }
    }
}
