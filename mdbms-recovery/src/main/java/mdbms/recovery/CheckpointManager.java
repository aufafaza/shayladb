package mdbms.recovery;

import mdbms.api.IDurableStorage;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecoveryResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CheckpointManager {
    private final LogManager logManager;
    private final IDurableStorage storageManager;
    private final RecordApplier recordApplier;
    private final String dataDirectory;
    private final String snapshotPath;

    public CheckpointManager(LogManager logManager, IDurableStorage storageManager, RecordApplier recordApplier, String dataDirectory, String snapshotPath) {
        this.logManager = logManager;
        this.storageManager = storageManager;
        this.recordApplier = recordApplier;
        this.dataDirectory = dataDirectory;
        this.snapshotPath = snapshotPath;
    }

    public RecoveryResult executeCheckpoint(List<Integer> activeTxIds) {
        logManager.flushUpTo(logManager.getCurrentLsn() - 1);
        
        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        
        CheckpointLogRecord cp = logManager.appendCheckpointLog(activeTxIds);
        
        return new RecoveryResult(
            true, 
            cp.getLsn(), 
            logManager.getMaxTxId(), 
            "Checkpoint created successfully"
        );
    }

    public RecoveryResult recoverToPointInTime(Instant targetTimestamp) {
        restoreSnapshot(); 

        List<LogRecord> allLogs = logManager.readLogs(null);
        
        Set<Integer> committedTxs = new HashSet<>();
        List<DataChangeLogRecord> recordsToRedo = new ArrayList<>();
        long lastProcessedLsn = -1;

        for (LogRecord record : allLogs) {
            if (record.getTimestamp().isAfter(targetTimestamp)) {
                break;
            }

            if (record.getType() == LogType.COMMIT) {
                committedTxs.add(record.getTxId());
            }
            
            if (record instanceof DataChangeLogRecord dataRecord) {
                recordsToRedo.add(dataRecord);
            }
            lastProcessedLsn = record.getLsn();
        }

        for (DataChangeLogRecord record : recordsToRedo) {
            if (committedTxs.contains(record.getTxId())) {
                recordApplier.redo(record);
            }
        }

        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        truncateLogAfter(lastProcessedLsn);

        return new RecoveryResult(
            true, 
            lastProcessedLsn, 
            logManager.getMaxTxId(), 
            "PITR completed up to: " + targetTimestamp
        );
    }

    public RecoveryResult recoverToTransaction(int targetTxId) {
        restoreSnapshot();

        List<LogRecord> allLogs = logManager.readLogs(null);
        Set<Integer> committedTxs = new HashSet<>();
        List<DataChangeLogRecord> recordsToRedo = new ArrayList<>();
        
        long targetCommitLsn = -1;

        // Cari record COMMIT milik targetTxId untuk mendapatkan batas targetCommitLsn
        for (LogRecord record : allLogs) {
            if (record.getTxId() == targetTxId && record.getType() == LogType.COMMIT) {
                targetCommitLsn = record.getLsn();
                break;
            }
        }
        
        if (targetCommitLsn == -1) {
            return new RecoveryResult(false, -1, logManager.getMaxTxId(), "Target transaction did not commit");
        }

        long lastProcessedLsn = -1;

        // Me-redo semua transaksi yang COMMIT pada lsn <= targetCommitLsn
        for (LogRecord record : allLogs) {
            if (record.getLsn() > targetCommitLsn) {
                break;
            }
            if (record.getType() == LogType.COMMIT) {
                committedTxs.add(record.getTxId());
            }
            if (record instanceof DataChangeLogRecord dataRecord) {
                recordsToRedo.add(dataRecord);
            }
            lastProcessedLsn = record.getLsn();
        }

        for (DataChangeLogRecord record : recordsToRedo) {
            if (committedTxs.contains(record.getTxId())) {
                recordApplier.redo(record);
            }
        }

        // Perbaikan Poin 4: Memanggil storageManager.flushBuffer() dan pemotongan log
        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        truncateLogAfter(lastProcessedLsn);

        return new RecoveryResult(
            true, 
            lastProcessedLsn, 
            logManager.getMaxTxId(), 
            "Recovery completed up to transaction " + targetTxId
        );
    }

    // Perbaikan Poin 5: Helper Pencari Checkpoint Terakhir untuk CrashRecoveryManager
    public CheckpointLogRecord findLastCompletedCheckpoint() {
        List<LogRecord> allLogs = logManager.readLogs(null);
        
        // Memindai mundur LogRecord bertipe LogType.CHECKPOINT
        for (int i = allLogs.size() - 1; i >= 0; i--) {
            LogRecord record = allLogs.get(i);
            if (record.getType() == LogType.CHECKPOINT && record instanceof CheckpointLogRecord cp) {
                return cp;
            }
        }
        return null;
    }

    private void restoreSnapshot() {
        // Integrasikan pemindahan/penimpaan file .tbl fisik di dataDirectory menggunakan snapshotPath
    }

    private void truncateLogAfter(long lsn) {
        // Pangkas/tulis ulang wal.log sehingga hanya menyisakan catatan log hingga titik target
    }
}