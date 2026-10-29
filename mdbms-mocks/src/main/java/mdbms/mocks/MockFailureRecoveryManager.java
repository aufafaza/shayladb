package mdbms.mocks;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.LogRecord;
import mdbms.models.LogType;
import mdbms.models.RecordImage;
import mdbms.models.RecoveryResult;
import mdbms.models.RowId;

/*
Implementasi Mock dari IFailureRecoveryManager yang mengembalikan dummy value
Disediakan untuk komponen Concurrency Control Manager (CCM) dan Storage Manager (SM)
*/

public class MockFailureRecoveryManager implements IFailureRecoveryManager {

    private final AtomicLong dummyLsnCounter = new AtomicLong(1L);
    private int lastMaxTxId = 0;

    public MockFailureRecoveryManager() {
    }

    @Override
    public RecoveryResult writeTransactionLog(int txId, LogType eventType) {
        updateMaxTxId(txId);
        long lsn = dummyLsnCounter.getAndIncrement();
        return new RecoveryResult(
            true,
            lsn,
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
        long lsn = dummyLsnCounter.getAndIncrement();
        return new RecoveryResult(
            true,
            lsn,
            lastMaxTxId,
            "MOCK: Data log [" + opType + "] on table '" + table + "' recorded for txId=" + txId
        );
    }

    @Override
    public boolean flushLogUpTo(long lsn) {
        return lsn >= 0;
    }

    @Override
    public RecoveryResult rollbackTransaction(int txId) {
        long lsn = dummyLsnCounter.getAndIncrement();
        return new RecoveryResult(
            true,
            lsn,
            lastMaxTxId,
            "MOCK: Rollback completed for txId=" + txId
        );
    }

    @Override
    public RecoveryResult createCheckpoint() {
        long lsn = dummyLsnCounter.getAndIncrement();
        return new RecoveryResult(
            true,
            lsn,
            lastMaxTxId,
            "MOCK: Checkpoint created successfully"
        );
    }

    @Override
    public RecoveryResult recoverOnStartup() {
        return new RecoveryResult(
            true,
            dummyLsnCounter.get(),
            lastMaxTxId,
            "MOCK: Startup crash recovery completed"
        );
    }

    @Override
    public RecoveryResult recoverToPointInTime(Instant targetTime) {
        return new RecoveryResult(
            true,
            dummyLsnCounter.get(),
            lastMaxTxId,
            "MOCK: Point-in-time recovery completed to " + targetTime
        );
    }

    @Override
    public RecoveryResult recoverToTransaction(int targetTxId) {
        return new RecoveryResult(
            true,
            dummyLsnCounter.get(),
            targetTxId,
            "MOCK: Recovery completed up to txId=" + targetTxId
        );
    }

    @Override
    public List<LogRecord> readLogs(Integer filterTxId) {
        return Collections.emptyList();
    }

    private synchronized void updateMaxTxId(int txId) {
        if (txId > this.lastMaxTxId) {
            this.lastMaxTxId = txId;
        }
    }
}
