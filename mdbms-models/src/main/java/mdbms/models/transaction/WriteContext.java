package mdbms.models.transaction;

import mdbms.models.data.RowId;

public class WriteContext {
    private final int txId;
    private final AccessStatus status;
    private final long lsn;
    private final String tableName;
    private final RowId rowId;

    public WriteContext(
        int txId,
        AccessStatus status,
        long lsn,
        String tableName,
        RowId rowId
    ) {
        this.txId = txId;
        this.status = status;
        this.lsn = lsn;
        this.tableName = tableName;
        this.rowId = rowId;
    }

    public int getTxId() {
        return txId;
    }

    public AccessStatus getStatus() {
        return status;
    }

    public long getLsn() {
        return lsn;
    }

    public String getTableName() {
        return tableName;
    }

    public RowId getRowId() {
        return rowId;
    }

    public boolean isGranted() {
        return status == AccessStatus.GRANTED;
    }

    public static WriteContext denied(int txId, AccessStatus status, String tableName, RowId rowId) {
        return new WriteContext(txId, status, -1L, tableName, rowId);
    }
}