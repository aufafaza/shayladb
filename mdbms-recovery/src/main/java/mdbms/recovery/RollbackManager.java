package mdbms.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.LockSupport;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecoveryResult;
import mdbms.models.log.TransactionLogRecord;

public class RollbackManager {
    private final LogManager logManager;
    private final RecordApplier recordApplier;
    private final IDurableStorage durableStorage;
    private final ReentrantLock rollbackLock = new ReentrantLock(true);
    private final Set<Integer> activeRollbacks = ConcurrentHashMap.newKeySet();
    private final Set<Integer> completedRollbacks = ConcurrentHashMap.newKeySet();

    public RollbackManager() {
        this(new LogManager(), (IDataAccess) null, null);
    }

    public RollbackManager(LogManager logManager) {
        this(logManager, (IDataAccess) null, null);
    }

    public RollbackManager(LogManager logManager, IDataAccess dataAccess) {
        this(
            logManager,
            new RecordApplier(dataAccess),
            dataAccess instanceof IDurableStorage durable ? durable : null
        );
    }

    public RollbackManager(LogManager logManager, IDataAccess dataAccess, IDurableStorage durableStorage) {
        this(logManager, new RecordApplier(dataAccess), durableStorage);
    }

    public RollbackManager(LogManager logManager, RecordApplier recordApplier) {
        this(
            logManager,
            recordApplier,
            recordApplier != null && recordApplier.getDataAccess() instanceof IDurableStorage durable
                ? durable
                : null
        );
    }

    public RollbackManager(LogManager logManager, RecordApplier recordApplier, IDurableStorage durableStorage) {
        this.logManager = Objects.requireNonNull(logManager, "logManager tidak boleh null");
        this.recordApplier = recordApplier != null ? recordApplier : new RecordApplier();
        this.durableStorage = durableStorage;
    }

    public RecoveryResult rollback(int txId) {
        if (txId <= 0) {
            return new RecoveryResult(
                false,
                -1L,
                logManager.getMaxTxId(),
                "ROLLBACK: Unknown transaction txId=" + txId
            );
        }

        if (!activeRollbacks.add(txId)) {
            return new RecoveryResult(
                false,
                -1L,
                logManager.getMaxTxId(),
                "ROLLBACK: Transaction txId=" + txId + " is already being undone"
            );
        }
        boolean interrupted = Thread.interrupted();
        rollbackLock.lock();
        try {
            interrupted |= Thread.interrupted();
            if (recordApplier.getDataAccess() == null || durableStorage == null) {
                return new RecoveryResult(
                    false,
                    -1L,
                    logManager.getMaxTxId(),
                    "ROLLBACK: Layanan storage (IDataAccess/IDurableStorage) tidak tersedia"
                );
            }
            if (completedRollbacks.contains(txId)) {
                return new RecoveryResult(
                    false,
                    -1L,
                    logManager.getMaxTxId(),
                    "ROLLBACK: Transaction txId=" + txId + " has already been undone"
                );
            }

            List<LogRecord> rawLogs = logManager.readLogs(txId);
            List<LogRecord> txLogs = new ArrayList<>();
            if (rawLogs != null) {
                for (LogRecord record : rawLogs) {
                    if (record != null && record.getTxId() == txId) {
                        txLogs.add(record);
                    }
                }
            }

            if (txLogs.isEmpty()) {
                return new RecoveryResult(
                    false,
                    -1L,
                    logManager.getMaxTxId(),
                    "ROLLBACK: Unknown transaction txId=" + txId
                );
            }

            for (LogRecord record : txLogs) {
                if (record.getType() == LogType.ABORT) {
                    completedRollbacks.add(txId);
                    return new RecoveryResult(
                        false,
                        record.getLsn(),
                        logManager.getMaxTxId(),
                        "ROLLBACK: Transaction txId=" + txId + " has already been undone"
                    );
                }
                if (record.getType() == LogType.COMMIT) {
                    return new RecoveryResult(
                        false,
                        record.getLsn(),
                        logManager.getMaxTxId(),
                        "ROLLBACK: Transaction txId=" + txId + " is already committed"
                    );
                }
            }

            try {
                for (int i = txLogs.size() - 1; i >= 0; i--) {
                    LogRecord record = txLogs.get(i);
                    if (record.getTxId() != txId) {
                        continue;
                    }
                    if (record instanceof DataChangeLogRecord change) {
                        boolean undone = undo(change);
                        if (!undone) {
                            return new RecoveryResult(
                                false,
                                change.getLsn(),
                                logManager.getMaxTxId(),
                                "ROLLBACK: Gagal melakukan undo pada log record LSN=" + change.getLsn() + " dengan txId=" + txId
                            );
                        }
                    }
                }

                flushBufferWithPinRetry();
                TransactionLogRecord abortRecord = logManager.appendTransactionLog(txId, LogType.ABORT);
                if (!logManager.flushUpTo(abortRecord.getLsn())) {
                    return new RecoveryResult(
                        false,
                        abortRecord.getLsn(),
                        logManager.getMaxTxId(),
                        "ROLLBACK: Gagal flush log ABORT untuk txId=" + txId
                    );
                }
                completedRollbacks.add(txId);

                return new RecoveryResult(
                    true,
                    abortRecord.getLsn(),
                    logManager.getMaxTxId(),
                    "ROLLBACK: Rollback selesai untuk txId=" + txId
                );
            } catch (RuntimeException e) {
                return new RecoveryResult(
                    false,
                    -1L,
                    logManager.getMaxTxId(),
                    "ROLLBACK: Gagal menerapkan rollback untuk txId=" + txId + ": " + e.getMessage()
                );
            }
        } finally {
            rollbackLock.unlock();
            activeRollbacks.remove(txId);
            if (interrupted) {                               // <--- 2. TAMBAH DI SINI
                Thread.currentThread().interrupt();
            }
        }
    }

    protected boolean undo(DataChangeLogRecord change) {
        return recordApplier.undo(change);
    }

    public boolean isRollingBack(int txId) {
        return activeRollbacks.contains(txId);
    }

    public boolean isAccessProtected() {
        return rollbackLock.isLocked();
    }

    private void flushBufferWithPinRetry() {
        final int maxAttempts = 50;
        final long backoffNanos = 2_000_000L; // 2 ms per percobaan, batas atas ~100 ms
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                durableStorage.flushBuffer();
                return;
            } catch (RuntimeException e) {
                if (!isTransientPinConflict(e) || attempt == maxAttempts) {
                    throw e;
                }
                parkUninterruptibly(backoffNanos);
            }
        }
    }

    private static boolean isTransientPinConflict(RuntimeException e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase().contains("still pinned")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static void parkUninterruptibly(long nanos) {
        boolean interrupted = Thread.interrupted();
        try {
            LockSupport.parkNanos(nanos);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
