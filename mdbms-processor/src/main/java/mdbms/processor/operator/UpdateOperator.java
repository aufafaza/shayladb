package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.transaction.WriteContext;
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;
import mdbms.processor.exception.QueryExecutionException;

public class UpdateOperator extends QueryUnaryOperator implements IWriteOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final String columnName;
    private final ExpressionTree newValue;
    private ResultTableSchema resultSchema;
    private List<String> columnNames;
    private int columnIndex;
    private DataType columnType;
    private List<RowId> targetRowIds;
    private int affectedRows;
    private boolean done;

    public UpdateOperator(IQueryOperator childOperator, IDataAccess dataAccess, ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControlManager, int transactionId, String tableName,
            String columnName, ExpressionTree newValue) {
        super(childOperator);
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
        this.tableName = tableName;
        this.columnName = columnName;
        this.newValue = newValue;
    }

    public void open() {
        var schema = schemaCatalog.getTableSchema(tableName);
        resultSchema = new ResultTableSchema(schema);
        List<ColumnDefinition> columns = schema.columns();
        done = false;
        affectedRows = 0;
        columnNames = columns.stream()
                .map(ColumnDefinition::name)
                .toList();
        columnIndex = columnNames.indexOf(columnName);
        columnType = columns.get(columnIndex).type();

        targetRowIds = new ArrayList<>();
        childOperator.open();
        IResultRow row;
        while ((row = childOperator.next()) != null) {
            targetRowIds.add(row.getRowId());
        }
        childOperator.close();
    }

    public IResultRow next() {
        if (done) return null;
        done = true;
        for (RowId targetId : targetRowIds) { // [CHANGED]
            DataItem item = new DataItem(tableName, targetId);
            // [ADDED] Acquire write protection BEFORE re-reading row and computing newValues
            AccessPermission.lockWrite(concurrencyControlManager, transactionId, item);

            Row current = dataAccess.fetchByRowId(targetId); // [CHANGED]
            if (current == null || current.values() == null || current.values().isEmpty()) { // [ADDED]
                continue;
            }
            List<Object> oldValues = new ArrayList<>(current.values());
            ResultRow protectedRow = new ResultRow(resultSchema, oldValues); // [CHANGED]
            protectedRow.setRowId(current.rowId());
            List<Object> newValues = new ArrayList<>(oldValues);
            newValues.set(columnIndex, ColumnTypes.toColumnType(ExpressionEvaluator.evaluate(newValue, protectedRow), columnType));

            if (dataAccess.canUpdateInPlace(current.rowId(), newValues)) {
                WriteContext ctx = AccessPermission.writeWithContext(
                        concurrencyControlManager, transactionId, item, oldValues, newValues);
                concurrencyControlManager.executeRelocation(transactionId, item, item, oldValues, newValues, () -> {
                    if (!dataAccess.updateInPlace(current.rowId(), newValues, ctx.getLSN())) {
                        throw new QueryExecutionException("Row no longer fits in place; retry the transaction");
                    }
                    return null;
                });
            } else {
                RowId destination = dataAccess.reserveRowId(tableName, newValues, 0L);
                try {
                    concurrencyControlManager.executeRelocation(transactionId, item,
                            new DataItem(tableName, destination), oldValues, newValues, relocationLsn -> { // [CHANGED]
                                dataAccess.insertAt(destination, newValues, relocationLsn); // [CHANGED]
                                dataAccess.deleteByRowId(current.rowId(), relocationLsn); // [CHANGED]
                                return null;
                            });
                } catch (RuntimeException failure) {
                    try {
                        dataAccess.releaseRowId(destination, 0L);
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            }
        }
        return null;
    }

    public void close() {
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}