package mdbms.processor.operator;

import mdbms.processor.ResultRow;

public interface IQueryOperator {
    void open();
    ResultRow next();
    void close();
}