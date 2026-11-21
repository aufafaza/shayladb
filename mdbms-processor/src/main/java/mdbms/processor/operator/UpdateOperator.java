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
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.exception.QueryExecutionException;

public class UpdateOperator extends QueryUnaryOperator implements IWriteOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final String columnName;
    private final ExpressionTree newValue;
    private List<String> columnNames;
    private int columnIndex;
    private DataType columnType;
    private List<IResultRow> targetRows;
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
        done = false;
        affectedRows = 0;
        List<ColumnDefinition> columns = schemaCatalog.getTableSchema(tableName).columns();
        columnNames = columns.stream()
                .map(ColumnDefinition::name)
                .toList();
        columnIndex = columnNames.indexOf(columnName);
        columnType = columns.get(columnIndex).type();

        targetRows = new ArrayList<>();
        childOperator.open();
        IResultRow row;
        while ((row = childOperator.next()) != null) {
            targetRows.add(row);
        }
        childOperator.close();
    }

    public IResultRow next() {
        if (done) return null;
        done = true;
        for (IResultRow row : targetRows) {
            DataItem item = new DataItem(tableName, row.getRowId());
            AccessPermission.lockWrite(concurrencyControlManager, transactionId, item);
            Row current = dataAccess.fetchByRowId(row.getRowId());
            List<Object> oldValues = new ArrayList<>(current.values());
            ResultRow protectedRow = new ResultRow(row.getSchema(), oldValues);
            protectedRow.setRowId(current.rowId());
            List<Object> newValues = new ArrayList<>(oldValues);
            newValues.set(columnIndex, ColumnTypes.toColumnType(ExpressionEvaluator.evaluate(newValue, protectedRow), columnType));
            // primary key baru ga boleh sama dengan baris lain, baris yang lagi di update sendiri dilewatin
            dataAccess.checkPrimaryKey(tableName, newValues, current.rowId());

            if (dataAccess.canUpdateInPlace(current.rowId(), newValues)) {
                concurrencyControlManager.executeRelocation(transactionId, item, item, oldValues, newValues, () -> {
                    if (!dataAccess.updateInPlace(current.rowId(), newValues)) {
                        // Abort instead of committing an UPDATE log whose row would silently move.
                        throw new QueryExecutionException("Row no longer fits in place; retry the transaction");
                    }
                    return null;
                });
            } else {
                RowId destination = dataAccess.reserveRowId(tableName, newValues);
                try {
                    concurrencyControlManager.executeRelocation(transactionId, item,
                            new DataItem(tableName, destination), oldValues, newValues, () -> {
                                dataAccess.insertAt(destination, newValues);
                                dataAccess.deleteByRowId(current.rowId());
                                return null;
                            });
                } catch (RuntimeException failure) {
                    try {
                        dataAccess.releaseRowId(destination);
                    } catch (RuntimeException cleanupFailure) {
                        // Undo may already have released it. Preserve any other cleanup error as well.
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            }
            affectedRows++;
        }
        return null;
    }

    public void close() {
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}
