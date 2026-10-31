package mdbms.processor.operator;

import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.models.AccessStatus;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataItem;
import mdbms.models.DataType;
import mdbms.models.DataWrite;
import mdbms.models.RowId;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;
import mdbms.models.DataItem;
import mdbms.models.DataWrite;
import mdbms.models.RowId;
import mdbms.processor.IResultRow;

public class InsertOperator implements IQueryOperator {
    private final IDataAccess dataAccess;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final DataWrite data;
    private boolean isDone;
    private int affectedRows;

    public InsertOperator(IDataAccess dataAccess, IConcurrencyControl concurrencyControlManager,
            int transactionId, DataWrite data) {
        this.dataAccess = dataAccess;
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
        this.data = data;
    }

    public void open() {
        isDone = false;
        affectedRows = 0;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        requestWrite(new DataItem(data.table(), null));
        List<RowId> rowIds = dataAccess.writeBlock(data);
        // TODO BUTUH method dari CCM untuk catatan perubahan ke FRM

        ResultTableSchema schema = new ResultTableSchema();
        schema.addColumn(new ColumnDefinition("affected_rows", DataType.INTEGER, 0, false));
        ResultRow result = new ResultRow(schema);
        result.set(schema.getTableName(), "affected_rows", rowIds.size());
        affectedRows = rowIds.size();
        return result;

        // TODO BUTUH ResultRow dari QP2 untuk mengembalikan jumlah baris yang ditambah
        // return null;
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
