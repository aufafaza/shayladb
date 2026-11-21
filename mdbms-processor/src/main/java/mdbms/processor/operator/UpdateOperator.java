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
        try {
            IResultRow row;
            while ((row = childOperator.next()) != null) {
                targetRows.add(row);
            }
        } finally {
            childOperator.close();
        }
    }

    public IResultRow next() {
        if (done) return null;
        done = true;

        for (IResultRow row : targetRows) {
            RowId oldRid = row.getRowId();
            DataItem oldItem = new DataItem(tableName, oldRid);

            Row current = dataAccess.fetchByRowId(oldRid);
            List<Object> oldValues = new ArrayList<>(current.values());
            ResultRow currentResult = new ResultRow(row.getSchema(), oldValues);
            currentResult.setRowId(oldRid);

            List<Object> newValues = new ArrayList<>(oldValues);
            newValues.set(columnIndex, ColumnTypes.toColumnType(ExpressionEvaluator.evaluate(newValue, currentResult), columnType));

            if (dataAccess.canUpdateInPlace(oldRid, newValues)) {
                AccessPermission.write(concurrencyControlManager, transactionId, oldItem, oldValues, newValues);

                if (dataAccess.updateInPlace(oldRid, newValues)) {
                    affectedRows++;
                    continue;
                } else {
                    throw new QueryExecutionException("Row no longer fits in place; retry the transaction");
                }
            }
            
            relocate(oldRid, oldItem, oldValues, newValues);
            affectedRows++;
        }
        return null;
    }

    private void relocate( RowId oldRid, DataItem oldItem, List<Object> oldValues, List<Object> newValues) {
        RowId newRid = dataAccess.reserveRowId(tableName, newValues);
        if (newRid == null) {
            throw new QueryExecutionException("Storage manager returned no destination RowId for UPDATE of " + oldRid);
        }
        if (newRid.equals(oldRid)) {
            dataAccess.releaseRowId(newRid);
            throw new QueryExecutionException("Storage manager reserved the original RowId as a relocation destination: " + oldRid);
        }

        DataItem newItem = new DataItem(tableName, newRid);
        try {
            concurrencyControlManager.executeRelocation(transactionId, oldItem, newItem, oldValues, newValues, () -> {
                dataAccess.insertAt(newRid, newValues);

                Row deleted = dataAccess.deleteByRowId(oldRid);
                if (deleted == null) {
                    throw new QueryExecutionException( "Original row disappeared during relocation: " + oldRid);
                }
                return null;
            });
        } catch (RuntimeException failure) {
            try {
                dataAccess.releaseRowId(newRid);
            } catch (RuntimeException cleanupFailure) {
                // Undo may already have released it. Preserve any other cleanup error as well.
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public void close() {
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}
