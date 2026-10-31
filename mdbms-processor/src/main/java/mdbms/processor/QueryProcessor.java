package mdbms.processor;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.IQueryOptimizer;
import mdbms.api.IQueryProcessor;
import mdbms.api.ISchemaCatalog;
import mdbms.models.ColumnDefinition;
import mdbms.models.ExecutionResult;
import mdbms.models.ParsedQuery;
import mdbms.processor.operator.IQueryOperator;

public class QueryProcessor implements IQueryProcessor {
    private IQueryOptimizer queryOptimizer;
    private IDataAccess dataAccess;
    private ISchemaCatalog schemaCatalog;
    private IFailureRecoveryManager failureRecovery;
    private IConcurrencyControl concurrencyControl;
    private PlanTranslator planTranslator;

    public QueryProcessor(
            IQueryOptimizer queryOptimizer,
            IDataAccess dataAccess,
            ISchemaCatalog schemaCatalog,
            IFailureRecoveryManager failureRecovery,
            IConcurrencyControl concurrencyControl) {
        this.queryOptimizer = queryOptimizer;
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.failureRecovery = failureRecovery;
        this.concurrencyControl = concurrencyControl;
        this.planTranslator = new PlanTranslator(
                queryOptimizer, dataAccess, schemaCatalog, failureRecovery, concurrencyControl);
    }

    @Override
    public ExecutionResult executeQuery(String query, int txId) {
        ParsedQuery parsedQuery = queryOptimizer.parseQuery(query);
        ParsedQuery optimizedQuery = queryOptimizer.optimizeQuery(parsedQuery);
        IQueryOperator translatedQuery = planTranslator.translate(optimizedQuery);
 
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        try {
            translatedQuery.open();
            boolean firstRow = true;
            ResultTableSchema tableSchema = null;
            IResultRow resultRow;
            while ((resultRow = translatedQuery.next()) != null) {
                // Get the schema
                if (firstRow) {
                    firstRow = false;
                    tableSchema = resultRow.getSchema();
                    for (ColumnDefinition definition : resultRow.getSchema().getColumns()) {
                        columns.add(definition.name());
                    }
                }

                List<Object> row = new ArrayList<>();
                for (ColumnDefinition definition : resultRow.getSchema().getColumns()) {
                    String tableName = tableSchema.getColumnTableOrigin(definition);
                    row.add(resultRow.get(tableName, definition.name()));
                }
            } 
            translatedQuery.close();
        } catch (Exception e) {
            concurrencyControl.abortTransaction(txId, "Query execution failed");
        }

        // TODO: count the affected rows
        ExecutionResult executionResult = new ExecutionResult(true, columns, rows, 0, "Query executed");
        return executionResult;
    }

}
