package mdbms.processor.operator;

import mdbms.processor.IResultRow;

public interface IQueryOperator {
    public void open();

    public IResultRow next();

    public void close();
}
