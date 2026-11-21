package mdbms.recovery;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
public class FailureRecoveryFacade implements IFailureRecoveryManager, AutoCloseable {

    private final LogManager logManager;
    private final RollbackManager rollbackManager;
    private final CheckpointManager checkpointManager;
    private final CrashRecoveryManager crashRecoveryManager;
    private final IDataAccess dataAccess;
    private final IDurableStorage durableStorage;
    private final ReentrantLock maintenanceLock = new ReentrantLock(true);
    private volatile BooleanSupplier maintenanceCoordinator;
    private volatile boolean offlineMaintenanceMode = false;

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

        WalFileHandler wal = new WalFileHandler(walFilePath);
        try {
            this.logManager = new LogManager(wal);
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
        } catch (RuntimeException | Error failure) {
            wal.close();
            throw failure;
        }
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
        maintenanceLock.lock();
        try {
            return checkpointManager.executeCheckpoint();
        } finally {
            maintenanceLock.unlock();
        }
    }

    @Override
    public RecoveryResult recoverOnStartup() {
        return crashRecoveryManager.performCrashRecovery();
    }

    @Override
    public RecoveryResult recoverToPointInTime(Instant targetTime) {
        maintenanceLock.lock();
        try {
            RecoveryResult blocked = checkMaintenanceBarrier();
            if (blocked != null) {
                return blocked;
            }
            return checkpointManager.recoverToPointInTime(targetTime);
        } finally {
            maintenanceLock.unlock();
        }
    }

    @Override
    public RecoveryResult recoverToTransaction(int targetTxId) {
        maintenanceLock.lock();
        try {
            RecoveryResult blocked = checkMaintenanceBarrier();
            if (blocked != null) {
                return blocked;
            }
            return checkpointManager.recoverToTransaction(targetTxId);
        } finally {
            maintenanceLock.unlock();
        }
    }

    @Override
    public List<LogRecord> readLogs(Integer filterTxId) {
        return logManager.readLogs(filterTxId);
    }

    public String formatLogs(Integer filterTxId) {
        return logManager.formatLogTable(readLogs(filterTxId));
    }

    @Override
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

    /**
     * Menghubungkan FRM dengan koordinator siklus hidup transaksi (CCM/DBMS).
     * Supplier wajib mengembalikan true HANYA jika admission dijeda dan transaksi aktif == 0.
     */
    public void attachMaintenanceCoordinator(BooleanSupplier maintenanceReadySupplier) {
        this.maintenanceCoordinator = maintenanceReadySupplier;
    }

    /**
     * Mengaktifkan/menonaktifkan mode maintenance offline (hanya untuk administrasi tanpa live DBMS).
     */
    public void setOfflineMaintenanceMode(boolean enabled) {
        if (enabled && maintenanceCoordinator != null) {
            throw new IllegalStateException(
                    "Tidak dapat memakai offline maintenance mode saat terhubung ke live maintenance coordinator");
        }
        this.offlineMaintenanceMode = enabled;
    }

    /** Entry point offline terkoordinasi untuk membuat snapshot baseline awal FR-05. */
    public void createBaselineSnapshot() {
        maintenanceLock.lock();
        try {
            if (maintenanceCoordinator != null && !maintenanceCoordinator.getAsBoolean()) {
                throw new IllegalStateException(
                        "Snapshot baseline ditolak: database harus berada dalam maintenance mode tanpa transaksi aktif");
            }
            checkpointManager.createSnapshot();
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** Entry point offline terkoordinasi untuk PITR (FR-05). */
    public RecoveryResult recoverToPointInTimeOffline(Instant targetTime) {
        return runInOfflineMaintenance(() -> checkpointManager.recoverToPointInTime(targetTime));
    }

    /** Entry point offline terkoordinasi untuk recovery hingga transaksi tertentu (FR-05). */
    public RecoveryResult recoverToTransactionOffline(int targetTxId) {
        return runInOfflineMaintenance(() -> checkpointManager.recoverToTransaction(targetTxId));
    }

    private RecoveryResult runInOfflineMaintenance(Supplier<RecoveryResult> action) {
        maintenanceLock.lock();
        try {
            if (maintenanceCoordinator != null) {
                return new RecoveryResult(
                        false,
                        -1L,
                        logManager.getMaxTxId(),
                        "HISTORICAL RECOVERY DITOLAK: gunakan maintenance coordinator live (bukan offline entry point)"
                );
            }
            boolean prev = offlineMaintenanceMode;
            offlineMaintenanceMode = true;
            try {
                return action.get();
            } finally {
                offlineMaintenanceMode = prev;
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    private RecoveryResult checkMaintenanceBarrier() {
        BooleanSupplier coordinator = this.maintenanceCoordinator;
        if (coordinator != null) {
            if (!coordinator.getAsBoolean()) {
                return new RecoveryResult(
                        false,
                        -1L,
                        logManager.getMaxTxId(),
                        "HISTORICAL RECOVERY DITOLAK: database harus berada dalam maintenance mode "
                                + "(admission dijeda dan tidak ada transaksi aktif)"
                );
            }
            return null;
        }
        if (!offlineMaintenanceMode) {
            return new RecoveryResult(
                    false,
                    -1L,
                    logManager.getMaxTxId(),
                    "HISTORICAL RECOVERY DITOLAK: panggilan langsung tanpa maintenance barrier tidak diizinkan; "
                            + "hubungkan maintenance coordinator atau gunakan entry point offline maintenance"
            );
        }
        return null;
    }
}
