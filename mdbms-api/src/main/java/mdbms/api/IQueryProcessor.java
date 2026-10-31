package mdbms.api;

import mdbms.models.ExecutionResult;

public interface IQueryProcessor {
    public ExecutionResult executeQuery(String query, int txId);
}
