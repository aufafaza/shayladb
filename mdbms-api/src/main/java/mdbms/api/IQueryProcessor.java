package mdbms.api;

import mdbms.models.query.ExecutionResult;

public interface IQueryProcessor {
    public ExecutionResult executeQuery(String query, int txId);
}
