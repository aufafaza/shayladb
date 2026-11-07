package mdbms.recovery;

import java.time.Instant;
import java.util.List;
import mdbms.models.data.RowId;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.TransactionLogRecord;

public class LogManager {

    public LogManager() {
    }

    public TransactionLogRecord appendTransactionLog(int txId, LogType eventType) {
        return new TransactionLogRecord(1L, txId, eventType, Instant.now());
    }

    public DataChangeLogRecord appendDataLog(
        int txId,
        LogType opType,
        String table,
        RowId rid,
        RecordImage beforeImg,
        RecordImage afterImg
    ) {
        return new DataChangeLogRecord(1L, txId, opType, Instant.now(), table, rid, beforeImg, afterImg);
    }

    public boolean flushUpTo(long lsn) {
        return lsn >= 0;
    }

    public List<LogRecord> readLogs(Integer filterTxId) {
        return List.of();
    }
}
