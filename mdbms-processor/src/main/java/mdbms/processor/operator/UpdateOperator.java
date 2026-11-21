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
        int updatedCount = 0;
        for (RowId targetId : targetRowIds) {
            DataItem item = new DataItem(tableName, targetId);
            // 1. Acquire exclusive write protection BEFORE capturing/revalidating old values
            AccessPermission.protectWrite(concurrencyControlManager, transactionId, item);

            // 2. Re-read protected current row to prevent stale computation after reader-to-writer upgrade
            Row currentRow = dataAccess.fetchByRowId(targetId);
            if (currentRow == null || currentRow.values() == null || currentRow.values().isEmpty()) {
                continue;
            }
            List<Object> oldValues = new ArrayList<>(currentRow.values());
            ResultRow protectedRow = new ResultRow(resultSchema, oldValues);
            protectedRow.setRowId(targetId);

            // 3. Compute new values from protected before-image
            List<Object> newValues = new ArrayList<>(oldValues);
            newValues.set(columnIndex,
                    ColumnTypes.toColumnType(ExpressionEvaluator.evaluate(newValue, protectedRow), columnType));

            // 4. Append WAL record and attach LSN to affected dirty pages
            WriteContext ctx = AccessPermission.writeWithContext(
                    concurrencyControlManager, transactionId, item, oldValues, newValues);

            // 5. Apply mutation using WAL LSN
            Row updated = dataAccess.updateByRowId(targetId, newValues, ctx.getLSN());
            if (!updated.rowId().equals(targetId)) {
                DataItem movedItem = new DataItem(tableName, updated.rowId());
                AccessPermission.protectWrite(concurrencyControlManager, transactionId, movedItem);
                WriteContext deleteOldCtx = AccessPermission.writeWithContext(
                        concurrencyControlManager, transactionId, item, newValues, null);
                WriteContext insertNewCtx = AccessPermission.writeWithContext(
                        concurrencyControlManager, transactionId, movedItem, null, newValues);
                // Ensure moved pages also carry the latest WAL LSN
                dataAccess.updateByRowId(updated.rowId(), newValues,
                        Math.max(deleteOldCtx.getLSN(), insertNewCtx.getLSN()));
            }
            updatedCount++;
        }
        affectedRows = updatedCount;
        return null;
    }

    public void close() {
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}