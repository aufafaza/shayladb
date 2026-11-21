package mdbms.recovery;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.RowId;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.log.TransactionLogRecord;
public class FailureRecoveryFacade implements IFailureRecoveryManager {

    private final LogManager logManager;
    private final RollbackManager rollbackManager;
    private final CheckpointManager checkpointManager;
    private final CrashRecoveryManager crashRecoveryManager;
    private final IDataAccess dataAccess;
    private final IDurableStorage durableStorage;

    public FailureRecoveryFacade(
        Path logDir,
        Path snapshotDir,
        IDataAccess dataAccess,
        IDurableStorage durableStorage
    ) {
        this.dataAccess = dataAccess;
        this.durableStorage = durableStorage;

        Path walFilePath = (logDir != null && logDir.toString().endsWith(".log"))
            ? logDir
            : (logDir != null ? logDir.resolve(WalFileHandler.DEFAULT_WAL_PATH) : Path.of(WalFileHandler.DEFAULT_WAL_PATH));
        Path dataDirPath = (logDir != null && logDir.toString().endsWith(".log"))
            ? logDir.getParent()
            : logDir;

        this.logManager = new LogManager(new WalFileHandler(walFilePath));
        RecordApplier recordApplier = new RecordApplier(dataAccess);
        this.rollbackManager = new RollbackManager(logManager, recordApplier, durableStorage);
        this.checkpointManager = new CheckpointManager(
            logManager,
            durableStorage,
            recordApplier,
            dataDirPath != null ? dataDirPath.toString() : null,
            snapshotDir != null ? snapshotDir.toString() : null
        );
        this.crashRecoveryManager = new CrashRecoveryManager(
            logManager,
            rollbackManager,
            checkpointManager,
            recordApplier,
            durableStorage
        );
    }

    public FailureRecoveryFacade(
        LogManager logManager,
        RollbackManager rollbackManager,
        CheckpointManager checkpointManager,
        CrashRecoveryManager crashRecoveryManager,
        IDataAccess dataAccess,
        IDurableStorage durableStorage
    ) {
        this.logManager = logManager;
        this.rollbackManager = rollbackManager;
        this.checkpointManager = checkpointManager;
        this.crashRecoveryManager = crashRecoveryManager;
        this.dataAccess = dataAccess;
        this.durableStorage = durableStorage;
    }

    @Override
    public RecoveryResult writeTransactionLog(int txId, LogType eventType) {
        if (txId <= 0 || eventType == null) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "Invalid txId or eventType");
        }
        try {
            TransactionLogRecord record = logManager.appendTransactionLog(txId, eventType);
            return new RecoveryResult(
                true,
                record.getLsn(),
                logManager.getMaxTxId(),
                "Transaction log " + eventType + " recorded for txId=" + txId
            );
        } catch (Exception e) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), e.getMessage());
        }
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
        if (txId <= 0 || opType == null || table == null || rid == null) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "Invalid data log parameters");
        }
        try {
            DataChangeLogRecord record = logManager.appendDataLog(txId, opType, table, rid, beforeImg, afterImg);
            return new RecoveryResult(
                true,
                record.getLsn(),
                logManager.getMaxTxId(),
                "Data log " + opType + " recorded on table " + table + " for txId=" + txId
            );
        } catch (Exception e) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), e.getMessage());
        }
    }

    @Override
    public RecoveryResult writeRelocationLog(
            int txId,
            String table,
            RowId oldRid,
            RowId newRid,
            RecordImage beforeImg,
            RecordImage afterImg) {
        if (txId <= 0 || table == null || oldRid == null || newRid == null) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "Invalid relocation log parameters");
        }
        if (oldRid.equals(newRid)) {
            return writeDataLog(txId, LogType.UPDATE, table, oldRid, beforeImg, afterImg);
        }
        try {
            DataChangeLogRecord record = logManager.appendRelocationLog(
                    txId, table, oldRid, newRid, beforeImg, afterImg);
            return new RecoveryResult(
                    true,
                    record.getLsn(),
                    logManager.getMaxTxId(),
                    "Relocation log UPDATE recorded on table " + table
                            + " (" + oldRid + " -> " + newRid + ") for txId=" + txId
            );
        } catch (Exception e) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), e.getMessage());
        }
    }

    @Override
    public boolean flushLogUpTo(long lsn) {
        return logManager.flushUpTo(lsn);
    }

    @Override
    public RecoveryResult rollbackTransaction(int txId) {
        return rollbackManager.rollback(txId);
    }

    @Override
    public RecoveryResult createCheckpoint() {
        return checkpointManager.executeCheckpoint();
    }

    @Override
    public RecoveryResult recoverOnStartup() {
        return crashRecoveryManager.performCrashRecovery();
    }

    @Override
    public RecoveryResult recoverToPointInTime(Instant targetTime) {
        return checkpointManager.recoverToPointInTime(targetTime);
    }

    @Override
    public RecoveryResult recoverToTransaction(int targetTxId) {
        return checkpointManager.recoverToTransaction(targetTxId);
    }

    @Override
    public List<LogRecord> readLogs(Integer filterTxId) {
        return logManager.readLogs(filterTxId);
    }

    public String formatLogs(Integer filterTxId) {
        return logManager.formatLogTable(readLogs(filterTxId));
    }

    public void close() {
        logManager.getWalFileHandler().close();
    }

    public LogManager getLogManager() {
        return logManager;
    }

    public RollbackManager getRollbackManager() {
        return rollbackManager;
    }

    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    public CrashRecoveryManager getCrashRecoveryManager() {
        return crashRecoveryManager;
    }

    public IDataAccess getDataAccess() {
        return dataAccess;
    }

    public IDurableStorage getDurableStorage() {
        return durableStorage;
    }
}