package mdbms.processor;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.IQueryOptimizer;
import mdbms.api.IQueryProcessor;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ExecutionResult;
import mdbms.models.query.ParsedQuery;
import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.exception.QueryExecutionException;
import mdbms.processor.exception.TransactionAbortedException;
import mdbms.processor.operator.IQueryOperator;
import mdbms.processor.operator.IWriteOperator;

public class QueryProcessor implements IQueryProcessor {
    private IQueryOptimizer queryOptimizer;
    private IConcurrencyControl concurrencyControl;
    private PlanTranslator planTranslator;

    public QueryProcessor(
            IQueryOptimizer queryOptimizer,
            IDataAccess dataAccess,
            ISchemaCatalog schemaCatalog,
            IFailureRecoveryManager failureRecovery,
            IConcurrencyControl concurrencyControl) {
        this.queryOptimizer = queryOptimizer;
        this.concurrencyControl = concurrencyControl;
        this.planTranslator = new PlanTranslator(dataAccess, schemaCatalog, concurrencyControl);
    }

    @Override
    public ExecutionResult executeQuery(String query, int txId) {
        ParsedQuery parsedQuery = queryOptimizer.parseQuery(query);
        ParsedQuery optimizedQuery = queryOptimizer.optimizeQuery(parsedQuery);
        IQueryOperator translatedQuery = planTranslator.translate(optimizedQuery, txId);

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
                rows.add(row);
            }
        } catch (QueryExecutionException e) {
            // transaksi yang dibatalkan CCM sudah dihapus CCM, abort lagi akan gagal
            if (!(e instanceof TransactionAbortedException)) {
                concurrencyControl.abortTransaction(txId, "Query execution failed");
            }
            ExecutionResult executionResult = new ExecutionResult(false, null, null, 0, e.getMessage());
            return executionResult;
        } catch (Exception e) {
            concurrencyControl.abortTransaction(txId, "Query execution failed");
            return new ExecutionResult(false, columns, rows, 0, e.getMessage());
        } finally {
            translatedQuery.close();
        }

        int affectedRows = 0;
        if (translatedQuery instanceof IWriteOperator) {
            affectedRows = ((IWriteOperator) translatedQuery).getAffectedRows();
        }
        ExecutionResult executionResult = new ExecutionResult(true, columns, rows, affectedRows, "Query executed");
        return executionResult;
    }

}
