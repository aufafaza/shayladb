package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.AccessStatus;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataItem;
import mdbms.processor.IResultRow;

public class UpdateOperator extends QueryUnaryOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final String columnName;
    private final Object newValue;
    private List<String> columnNames;
    private int columnIndex;
    private List<IResultRow> targetRows;
    private boolean isDone;
    private int affectedRows;

    public UpdateOperator(IQueryOperator childOperator, IDataAccess dataAccess, ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControlManager, int transactionId, String tableName,
            String columnName, Object newValue) {
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
        columnNames = schemaCatalog.getTableSchema(tableName).columns().stream()
                .map(ColumnDefinition::name)
                .toList();
        columnIndex = columnNames.indexOf(columnName);

        targetRows = new ArrayList<>();
        childOperator.open();
        IResultRow row;
        while ((row = childOperator.next()) != null) {
            targetRows.add(row);
        }
        childOperator.close();
        isDone = false;
        affectedRows = 0;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        for (IResultRow row : targetRows) {
            requestWrite(new DataItem(tableName, row.getRowId()));
            List<Object> newValues = new ArrayList<>();
            for (String name : columnNames) {
                newValues.add(row.get(name));
            }
            // TODO BUTUH ExpressionEvaluator dari QP2
            newValues.set(columnIndex, newValue);
            // TODO BUTUH method dari CCM untuk kirim catatan perubahan ke FRM
            dataAccess.updateByRowId(row.getRowId(), newValues);
        }
        affectedRows = targetRows.size();

        // TODO BUTUH ResultRow dari QP2 untuk return jumlah baris yang diubah
        return null;
    }

    public void close() {
    }

    public int getAffectedRows() {
        return affectedRows;
    }

    private void requestWrite(DataItem item) {
        AccessStatus status = concurrencyControlManager.requestWrite(transactionId, item);
        while (status == AccessStatus.WAITING) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new IllegalStateException("Interrupted while waiting for CCM", e);
            }
            status = concurrencyControlManager.requestWrite(transactionId, item);
        }
        if (status == AccessStatus.ABORTED) {
            throw new IllegalStateException("Transaction " + transactionId + " was aborted by CCM");
        }
    }
}
