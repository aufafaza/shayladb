package mdbms.processor.operator;

import mdbms.processor.IResultRow;
import mdbms.processor.ResultTableSchema;

public class LimitOperator extends QueryUnaryOperator implements IRowProducingOperator {
    private final long limit;
    private long count;

    public LimitOperator(IQueryOperator child, long limit) {
        super(child);
        if (limit < 0) throw new IllegalArgumentException("LIMIT tidak boleh negatif");
        this.limit = limit;
    }

    @Override
    public ResultTableSchema getSchema() {
        if (childOperator instanceof IRowProducingOperator) {
            return ((IRowProducingOperator) childOperator).getSchema();
        }
        return null;
    }

    @Override
    public void open() { count = 0; childOperator.open(); }

    @Override
    public IResultRow next() {
        if (count >= limit) return null;
        IResultRow row = childOperator.next();
        if (row != null) count++;
        return row;
    }

    @Override
    public void close() {
        try { childOperator.close(); }
        finally { count = 0; }
    }
}
