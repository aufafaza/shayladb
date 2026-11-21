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
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;
import mdbms.processor.exception.QueryExecutionException;
import mdbms.processor.operator.IQueryMessage;
import mdbms.processor.operator.IQueryOperator;
import mdbms.processor.operator.IRowProducingOperator;
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
        IQueryOperator translatedQuery = null;
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        try {
            // parse sama translate ikut di dalam try biar statement yang ditolak optimizer juga ngebatalin transaksinya
            ParsedQuery parsedQuery = queryOptimizer.parseQuery(query);
            ParsedQuery optimizedQuery = queryOptimizer.optimizeQuery(parsedQuery);
            translatedQuery = planTranslator.translate(optimizedQuery, txId);
            translatedQuery.open();
            boolean firstRow = true;
            ResultTableSchema tableSchema = null;
            if (translatedQuery instanceof IRowProducingOperator) {
                tableSchema = ((IRowProducingOperator) translatedQuery).getSchema();
                if (tableSchema != null) {
                    for (ColumnDefinition definition : tableSchema.getColumns()) {
                        columns.add(definition.name());
                    }
                    firstRow = false;
                }
            }
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
            abortIfActive(txId);
            ExecutionResult executionResult = new ExecutionResult(false, null, null, 0, "Error: " + e.getMessage());
            return executionResult;
        } catch (Exception e) {
            abortIfActive(txId);
            return new ExecutionResult(false, columns, rows, 0, "Error: " + e.getMessage());
        } finally {
            if (translatedQuery != null) translatedQuery.close();
        }

        int affectedRows = -1;
        String message = "";
        if (translatedQuery instanceof IWriteOperator) {
            affectedRows = ((IWriteOperator) translatedQuery).getAffectedRows();
        }
        if (translatedQuery instanceof IQueryMessage) {
            message = ((IQueryMessage) translatedQuery).getMessage();
        }
        ExecutionResult executionResult = new ExecutionResult(true, columns, rows, affectedRows, message);
        return executionResult;
    }

    // statement gagal berarti seluruh transaksi dibatalkan, tapi skip kalau CCM udah duluan nutup transaksinya
    private void abortIfActive(int txId) {
        Transaction tx = concurrencyControl.getTransaction(txId);
        if (tx != null && tx.getState() == TransactionState.ACTIVE) {
            concurrencyControl.abortTransaction(txId, "Query execution failed");
        }
    }

}
