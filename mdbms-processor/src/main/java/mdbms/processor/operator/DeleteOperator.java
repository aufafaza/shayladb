package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.transaction.WriteContext;
import mdbms.processor.IResultRow;

public class DeleteOperator extends QueryUnaryOperator implements IWriteOperator {
    private final IDataAccess access;
    private final IConcurrencyControl concurrency;
    private final int txId;
    private final String table;
    private List<RowId> targetRowIds;
    private int affectedRows;

    public DeleteOperator(IQueryOperator child,IDataAccess a,IConcurrencyControl cc,int tx,String t){
        super(child);
        access = a;
        concurrency = cc;
        txId = tx;
        table = t;
    }

    @Override
    public void open() {
        targetRowIds = new ArrayList<>();
        childOperator.open();
        IResultRow r;
        while ((r = childOperator.next()) != null) {
            targetRowIds.add(r.getRowId());
        }
        childOperator.close();
    }

    @Override
    public IResultRow next() {
        int deletedCount = 0;
        for (RowId rowId : targetRowIds) {
            DataItem item = new DataItem(table, rowId);
            // 1. Protect data before consuming/validating before-image
            AccessPermission.lockWrite(concurrency, txId, item);

            // 2. Capture valid before-image under write protection
            Row currentRow = access.fetchByRowId(rowId);
            if (currentRow == null || currentRow.values() == null || currentRow.values().isEmpty()) {
                continue;
            }
            List<Object> oldValues = new ArrayList<>(currentRow.values());

            // 3. Log DELETE record and obtain WAL LSN
            WriteContext ctx = AccessPermission.writeWithContext(concurrency, txId, item, oldValues, null);

            // 4. Delete row and associate WAL LSN with dirty pages
            access.deleteByRowId(rowId, ctx.getLSN());
            deletedCount++;
        }
        affectedRows = deletedCount;
        return null;
    }

    @Override public void close(){
    }

    public int getAffectedRows(){
        return affectedRows;
    }
}