package mdbms.recovery;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecoveryResult;
import mdbms.models.log.TransactionLogRecord;

/**
 * Mengelola pemulihan otomatis pasca-crash (Analysis, Redo, dan Undo),
 * memastikan properti Durability mDBMS terjaga.
 */
public class CrashRecoveryManager {

    private final LogManager logManager;
    private final RollbackManager rollbackManager;
    private final CheckpointManager checkpointManager;
    private final RecordApplier recordApplier;
    private final IDurableStorage durableStorage;

    public CrashRecoveryManager(
            LogManager logManager,
            RollbackManager rollbackManager,
            CheckpointManager checkpointManager,
            IDataAccess dataAccess,
            IDurableStorage durableStorage) {
        this.logManager = logManager;
        this.rollbackManager = rollbackManager;
        this.checkpointManager = checkpointManager;
        this.recordApplier = new RecordApplier(dataAccess);
        this.durableStorage = durableStorage;
    }

    /**
     * Melakukan sinkronisasi log dengan durable storage
     * 
     * @apiNote Hanya dipanggil sekali ketika mDBMS diinisialisasi
     * @return hasil recovery
     */
    public RecoveryResult performCrashRecovery() {
        List<LogRecord> allLogs = logManager.readLogs(null);
        if (allLogs.isEmpty()) {
            return new RecoveryResult(true, 0L, logManager.getMaxTxId(), "RECOVERY: Log kosong, tidak ada pemulihan");
        }

        // Cari indeks CHECKPOINT terakhir yang selesai
        CheckpointLogRecord lastCp = checkpointManager != null
                ? checkpointManager.findLastCompletedCheckpoint()
                : null;

        int startIdx = 0;
        if (lastCp != null) {
            for (int i = allLogs.size() - 1; i >= 0; i--) {
                if (allLogs.get(i).getLsn() == lastCp.getLsn()) {
                    startIdx = i + 1;
                    break;
                }
            }
        }

        AnalysisState state = runAnalysisPhase(allLogs, lastCp, startIdx);

        boolean redoOk = runRedoPhase(allLogs, startIdx, state.committedTxs);
        if (!redoOk) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "RECOVERY: Gagal pada fase Redo");
        }

        boolean undoOk = runUndoPhase(allLogs, state.activeTxs);
        if (!undoOk) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "RECOVERY: Gagal pada fase Undo");
        }

        // Pastikan seluruh hasil pemulihan tersimpan permanen di disk sebelum menulis
        // ABORT final
        if (durableStorage != null) {
            durableStorage.flushBuffer();
        }

        long finalLsn = allLogs.get(allLogs.size() - 1).getLsn();
        for (Integer uncommittedTxId : state.activeTxs) {
            TransactionLogRecord abortRec = logManager.appendTransactionLog(uncommittedTxId, LogType.ABORT);
            finalLsn = abortRec.getLsn();
        }
        logManager.flushUpTo(finalLsn);

        return new RecoveryResult(
                true,
                finalLsn,
                logManager.getMaxTxId(),
                "RECOVERY: Crash recovery selesai (Redo=" + state.committedTxs + ", Undo=" + state.activeTxs + ")");
    }

    /**
     * Nentuin transaksi mana yang commit, abort, sama active
     * 
     * @param logs
     * @param lastCp
     * @param startIdx
     * @return list id transaction yang commit, abort, dan active
     */
    private AnalysisState runAnalysisPhase(List<LogRecord> logs, CheckpointLogRecord lastCp, int startIdx) {
        Set<Integer> committedTxs = new HashSet<>();
        Set<Integer> abortedTxs = new HashSet<>();
        Set<Integer> activeTxs = new LinkedHashSet<>();

        if (lastCp != null && lastCp.getActiveTxIds() != null) {
            activeTxs.addAll(lastCp.getActiveTxIds());
        }

        for (int i = startIdx; i < logs.size(); i++) {
            LogRecord record = logs.get(i);
            int txId = record.getTxId();
            if (txId <= 0)
                continue;

            switch (record.getType()) {
                case BEGIN, INSERT, UPDATE, DELETE -> {
                    if (!committedTxs.contains(txId) && !abortedTxs.contains(txId)) {
                        activeTxs.add(txId);
                    }
                }
                case COMMIT -> {
                    activeTxs.remove(txId);
                    committedTxs.add(txId);
                }
                case ABORT -> {
                    activeTxs.remove(txId);
                    abortedTxs.add(txId);
                }
                default -> {
                }
            }
        }
        return new AnalysisState(committedTxs, abortedTxs, activeTxs);
    }

    /**
     * Rerun perubahan transaksi yang commit sejak setelah Checkpoint
     * 
     * @param logs
     * @param startIdx
     * @param committedTxs
     * @return berhasil atau tidak
     */
    private boolean runRedoPhase(List<LogRecord> logs, int startIdx, Set<Integer> committedTxs) {
        for (int i = startIdx; i < logs.size(); i++) {
            LogRecord record = logs.get(i);
            if (record instanceof DataChangeLogRecord change && committedTxs.contains(change.getTxId())) {
                if (!recordApplier.redo(change)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Revert perubahan transaksi yang aktif ketika crash secara mundur
     * 
     * @param logs
     * @param activeTxs
     * @return berhasil atau tidak
     */
    private boolean runUndoPhase(List<LogRecord> logs, Set<Integer> activeTxs) {
        if (activeTxs.isEmpty()) {
            return true;
        }
        // Scan mundur berdasarkan urutan LSN terbalik agar undo antar-transaksi tidak
        // saling menimpa
        for (int i = logs.size() - 1; i >= 0; i--) {
            LogRecord record = logs.get(i);
            if (record instanceof DataChangeLogRecord change && activeTxs.contains(change.getTxId())) {
                boolean undone = rollbackManager != null
                        ? rollbackManager.undo(change)
                        : recordApplier.undo(change);
                if (!undone) {
                    return false;
                }
            }
        }
        return true;
    }

    private record AnalysisState(
            Set<Integer> committedTxs,
            Set<Integer> abortedTxs,
            Set<Integer> activeTxs) {
    }
}