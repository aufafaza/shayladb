package mdbms.recovery;

import mdbms.api.IFailureRecoveryManager;
import mdbms.models.DataChangeLogRecord;
import mdbms.models.LogRecord;
import mdbms.models.LogType;
import mdbms.models.RecordImage;
import mdbms.models.RecoveryResult;
import mdbms.models.RowId;
import mdbms.models.TransactionLogRecord;

import java.time.Instant;
import java.util.List;

public class FailureRecoveryManager implements IFailureRecoveryManager{

    private static final long MOCK_LSN = 1L;

    private int lastMaxTxId = 0;

    @Override
    public RecoveryResult writeTransactionLog(int txId, LogType eventType){
        updateMaxTxId(txId);
        return new RecoveryResult(
            true,
            MOCK_LSN,
            lastMaxTxId,
            "MOCK: Transaction log [" + eventType + "] recorded for txId=" + txId
        );
    }

    @Override
    public RecoveryResult writeDataLog(
        int txId,
        LogType opType,
        String table,
        RowId rid,
        RecordImage beforeImg,
        RecordImage afterImg
    ) {
        updateMaxTxId(txId);
        return new RecoveryResult(
            true,
            MOCK_LSN,
            lastMaxTxId,
            "MOCK: Data log [" + opType + "] on table '" + table + "' recorded for txId=" + txId
        );
    }

    @Override
    public boolean flushLogUpTo(long lsn){
        return lsn >= 0;
    }

    @Override
    public RecoveryResult rollbackTransaction(int txId) {
        return new RecoveryResult(
            true,
            MOCK_LSN,
            lastMaxTxId,
            "MOCK: Rollback completed for txId=" + txId
        );
    }

    @Override
    public RecoveryResult createCheckpoint(){
        return new RecoveryResult(
            true,
            MOCK_LSN,
            lastMaxTxId,
            "MOCK: Checkpoint created successfully"
        );
    }

    @Override
    public RecoveryResult recoverOnStartup() {
        return new RecoveryResult(
            true,
            MOCK_LSN,
            lastMaxTxId,
            "MOCK: Startup crash recovery completed"
        );
    }

    @Override
    public RecoveryResult recoverToPointInTime(Instant targetTime) {
        return new RecoveryResult(
            true,
            MOCK_LSN,
            lastMaxTxId,
            "MOCK: Point-in-time recovery completed to " + targetTime
        );
    }

    @Override
    public RecoveryResult recoverToTransaction(int targetTxId) {
        return new RecoveryResult(
            true,
            MOCK_LSN,
            targetTxId,
            "MOCK: Recovery completed up to txId=" + targetTxId
        );
    }

    @Override
    public List<LogRecord> readLogs(Integer filterTxId) {
        // Dummy records; the filter only picks which txId they are stamped with
        int txId = filterTxId == null ? 1 : filterTxId;
        Instant now = Instant.now();
        return List.of(
            new TransactionLogRecord(1L, txId, LogType.BEGIN, now),
            new DataChangeLogRecord(
                2L,
                txId,
                LogType.UPDATE,
                now,
                "mock_table",
                new RowId("mock_table", 0, 0),
                new RecordImage(List.of("before")),
                new RecordImage(List.of("after"))
            ),
            new TransactionLogRecord(3L, txId, LogType.COMMIT, now)
        );
    }

    private synchronized void updateMaxTxId(int txId) {
        if (txId > this.lastMaxTxId) {
            this.lastMaxTxId = txId;
        }
    }

}
