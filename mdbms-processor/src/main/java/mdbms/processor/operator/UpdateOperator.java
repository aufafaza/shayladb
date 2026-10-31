package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.AccessStatus;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataItem;
import mdbms.models.Row;
import mdbms.processor.IResultRow;

public class UpdateOperator extends QueryUnaryOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final String columnName;
    private final Object newValue;
    private int columnIndex;
    private List<Row> targetRows;
    private boolean isDone;
    private int affectedRows;

    public UpdateOperator(IQueryOperator childOperator, IDataAccess dataAccess, ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControlManager, int transactionId, String tableName,
            String columnName, Object newValue) {
        // TODO BUTUH QueryUnaryOperator  QP2
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
        this.tableName = tableName;
        this.columnName = columnName;
        this.newValue = newValue;
    }

    public void open() {
        columnIndex = schemaCatalog.getTableSchema(tableName).columns().stream()
                .map(ColumnDefinition::name)
                .toList()
                .indexOf(columnName);

        targetRows = new ArrayList<>();
        // TODO BUTUH open()/next()/close() IQueryOperator, IResultRow dengan RowId + nilai kolom. QP2
        isDone = false;
        affectedRows = 0;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        for (Row row : targetRows) {
            requestWrite(new DataItem(tableName, row.rowId()));
            List<Object> newValues = new ArrayList<>(row.values());
            // TODO BUTUH ExpressionEvaluator dari QP2
            newValues.set(columnIndex, newValue);
            // TODO BUTUH method dari CCM untuk kirim catatan perubahan ke FRM
            dataAccess.updateByRowId(row.rowId(), newValues);
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
