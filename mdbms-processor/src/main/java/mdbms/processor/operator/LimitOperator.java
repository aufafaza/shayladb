package mdbms.processor.operator;

import mdbms.processor.ResultRow;

public class LimitOperator implements IQueryOperator {

    private final IQueryOperator childOperator;
    private final long limit;
    private long count;

    public LimitOperator(IQueryOperator childOperator, long limit) {
        if (childOperator == null) {
            throw new IllegalArgumentException("Child operator cannot be null");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("LIMIT cannot be negative");
        }

        this.childOperator = childOperator;
        this.limit = limit;
    }

    @Override
    public void open() {
        count = 0;
        childOperator.open();
    }

    @Override
    public ResultRow next() {
        if (count >= limit) {
            return null;
        }

        ResultRow row = childOperator.next();

        if (row == null) {
            return null;
        }

        count++;
        return row;
    }

    @Override
    public void close() {
        try {
            childOperator.close();
        } finally {
            count = 0;
        }
    }
}