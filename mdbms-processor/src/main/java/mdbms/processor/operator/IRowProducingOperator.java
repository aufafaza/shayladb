package mdbms.processor.operator;

import mdbms.processor.ResultTableSchema;

public interface IRowProducingOperator {
    public ResultTableSchema getSchema();
}
