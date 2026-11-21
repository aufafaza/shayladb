package mdbms.models.log;

import java.time.Instant;

import mdbms.models.data.RowId;

public class DataChangeLogRecord extends LogRecord {
    private final String table;
    private final RowId rowId;
    private final RowId newRowId;
    private final RecordImage beforeImage;
    private final RecordImage afterImage;

    public DataChangeLogRecord(
        long lsn,
        int txId,
        LogType type,
        Instant timestamp,
        String table,
        RowId rowId,
        RecordImage beforeImage,
        RecordImage afterImage
    ) {
        this(lsn, txId, type, timestamp, table, rowId, null, beforeImage, afterImage);
    }

    public DataChangeLogRecord(
        long lsn,
        int txId,
        LogType type,
        Instant timestamp,
        String table,
        RowId rowId,
        RowId newRowId,
        RecordImage beforeImage,
        RecordImage afterImage
    ) {
        super(lsn, txId, type, timestamp);
        this.table = table;
        this.rowId = rowId;
        this.newRowId = newRowId;
        this.beforeImage = beforeImage;
        this.afterImage = afterImage;
    }

    public String getTable() {
        return table;
    }

    public RowId getRowId() {
        return rowId;
    }

    public RowId getNewRowId() {
        return newRowId;
    }

    public boolean isRelocation() {
        return getType() == LogType.UPDATE
            && rowId != null
            && newRowId != null
            && !newRowId.equals(rowId);
    }

    public RecordImage getBeforeImage() {
        return beforeImage;
    }

    public RecordImage getAfterImage() {
        return afterImage;
    }
}