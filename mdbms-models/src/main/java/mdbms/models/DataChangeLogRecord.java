package mdbms.models;

import java.time.Instant;

public class DataChangeLogRecord extends LogRecord {
    private final String table;
    private final RowId rowId;
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
        super(lsn, txId, type, timestamp);
        this.table = table;
        this.rowId = rowId;
        this.beforeImage = beforeImage;
        this.afterImage = afterImage;
    }

    public String getTable() {
        return table;
    }

    public RowId getRowId() {
        return rowId;
    }

    public RecordImage getBeforeImage() {
        return beforeImage;
    }

    public RecordImage getAfterImage() {
        return afterImage;
    }
}
