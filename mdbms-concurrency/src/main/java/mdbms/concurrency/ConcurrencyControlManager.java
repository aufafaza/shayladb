package mdbms.concurrency;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.DataItem;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;
import mdbms.models.transaction.WriteContext;

/**
 * CC1 transaction manager and facade. It owns transaction lifecycle and
 * delegates access decisions to the selected protocol strategy.
 */
public final class ConcurrencyControlManager implements IConcurrencyControl {
    private final AtomicInteger transactionCounter = new AtomicInteger(1);
    private final AtomicLong timestampCounter = new AtomicLong(System.currentTimeMillis());
    private final Map<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private final IFailureRecoveryManager frm;
    private final Object lifecycleMonitor = new Object();
    private volatile ConcurrencyProtocol protocol;
    private volatile boolean isRecovered = true;
    private volatile boolean acceptingTransactions = true;
    private volatile boolean stopping;
    private volatile String failureReason;

    /** Uses timestamp ordering when no startup configuration is supplied. */
    public ConcurrencyControlManager() {
        this(new TimestampsBasedProtocol(), null);
    }

    public ConcurrencyControlManager(ConcurrencyProtocol protocol) {
        this(protocol, null);
    }

    public ConcurrencyControlManager(IFailureRecoveryManager frm, ConcurrencyProtocol protocol) {
        this(protocol, frm);
    }

    public ConcurrencyControlManager(ConcurrencyProtocol protocol, IFailureRecoveryManager frm) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.frm = frm;
        configureProtocol(protocol);
    }

    public void setProtocol(ConcurrencyProtocol protocol) {
        Objects.requireNonNull(protocol, "protocol");
        synchronized (lifecycleMonitor) {
            if (!activeTransactions.isEmpty()) {
                throw new IllegalStateException("Cannot change protocol while transactions are active");
            }
            this.protocol = protocol;
            configureProtocol(protocol);
        }
    }

    /** Returns the strategy currently used by the CCM facade. */
    public ConcurrencyProtocol getProtocol() { return protocol; }

    /** Returns a stable display name for startup diagnostics and monitoring. */
    public String getActiveProtocolName() { return protocol.getClass().getSimpleName(); }

    /** Wait for a begin call already inside the admission gate to finish. */
    public void awaitAdmissionStopped() {
        synchronized (lifecycleMonitor) {
            if (acceptingTransactions && !stopping) throw new IllegalStateException("Admission is still open");
        }
    }

    public void stopTransactions() {
        stopping = true;
        pauseTransactions();
    }

    @Override public boolean isStopping() { return stopping; }

    public void pauseTransactions() {
        acceptingTransactions = false;
    }

    /** Allows new transactions after a checkpoint or maintenance operation. */
    public void resumeTransactions() {
        synchronized (lifecycleMonitor) {
            if (stopping) throw new IllegalStateException("Engine is stopping");
            acceptingTransactions = true;
            lifecycleMonitor.notifyAll();
        }
    }

    // tunggu sampai semua transaksi selesai, langsung gagal kalau engine udah failed biar checkpoint ga nunggu selamanya
    public void awaitNoActiveTransactions() throws InterruptedException {
        synchronized (lifecycleMonitor) {
            while (!activeTransactions.isEmpty()) {
                requireNotFailed();
                if (stopping) throw new IllegalStateException("Engine is stopping");
                lifecycleMonitor.wait(50);
            }
        }
    }

    // true kalau ada transaksi yang hasil akhirnya ga pasti, engine harus restart dan crash recovery yang nentuin
    public boolean isFailed() {
        return failureReason != null;
    }

    // alasan engine masuk failed state, null kalau engine masih sehat
    public String getFailureReason() {
        return failureReason;
    }

    public int getActiveTransactionCount() { return activeTransactions.size(); }

    public int getLockCount() {
        if (protocol instanceof LockBasedProtocol lockBased) {
            return lockBased.getLockManager().getLockTableSnapshot().values().stream()
                    .mapToInt(java.util.List::size)
                    .sum();
        }
        return 0;
    }

    public int getWaitingRequestCount() {
        if (protocol instanceof LockBasedProtocol lockBased) {
            return lockBased.getLockManager().getWaitingRequestsSnapshot().size();
        }
        return 0;
    }

    public boolean isAcceptingTransactions() {
        synchronized (lifecycleMonitor) {
            return acceptingTransactions && !stopping;
        }
    }

    /**
     * Completes crash recovery before accepting new transactions. Recovery is
     * deliberately explicit so the composition root can initialize FRM once.
     */
    public void initAfterRecovery() {
        synchronized (lifecycleMonitor) {
            isRecovered = false;
        }
        if (frm == null) {
            synchronized (lifecycleMonitor) {
                isRecovered = true;
            }
            return;
        }
        RecoveryResult result = frm.recoverOnStartup();
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("Failure recovery did not complete");
        }
        transactionCounter.updateAndGet(current -> Math.max(current, result.getMaxTxId() + 1));
        long now = System.currentTimeMillis();
        timestampCounter.updateAndGet(current -> Math.max(now, current + 1));
        synchronized (lifecycleMonitor) {
            isRecovered = true;
            lifecycleMonitor.notifyAll();
        }
    }

    public boolean isRecovered() {
        return isRecovered;
    }

    @Override
    public int beginTransaction() {
        synchronized (lifecycleMonitor) {
            requireNotFailed();
            if (!isRecovered) throw new IllegalStateException("Recovery has not completed");
            if (stopping || !acceptingTransactions) throw new IllegalStateException("Transaction admission is paused");
            int id = transactionCounter.getAndIncrement();
            long now = System.currentTimeMillis();
            long ts = timestampCounter.updateAndGet(prev -> Math.max(now, prev + 1));
            Transaction tx = new Transaction(id, ts);
            if (frm != null) {
                RecoveryResult result = frm.writeTransactionLog(id, LogType.BEGIN);
                if (result == null || !result.isSuccess()) {
                    throw new IllegalStateException("Unable to persist begin log for transaction " + id);
                }
            }
            activeTransactions.put(id, tx);
            return id;
        }
    }

    @Override
    public void commitTransaction(int txId) {
        Transaction tx = requireTransaction(txId);
        synchronized (tx) {
            requireState(tx, TransactionState.ACTIVE);
            tx.setState(TransactionState.PARTIALLY_COMMITTED);
            RuntimeException commitFailure = null;
            boolean logged;
            try {
                logged = persistCommitLog(txId);
            } catch (RuntimeException failure) {
                commitFailure = failure;
                logged = false;
            }
            if (!logged) {
                IllegalStateException failure = new IllegalStateException(
                        "Unable to persist commit log for transaction " + txId, commitFailure);
                // commit belum pasti durable jadi coba rollback, kalau rollback ditolak hasilnya ga pasti dan engine failed
                failAndAbort(tx, "Unable to persist commit log", failure);
                throw failure;
            }
            protocol.releaseResources(tx);
            tx.setState(TransactionState.COMMITTED);
            synchronized (lifecycleMonitor) {
                activeTransactions.remove(txId, tx);
                lifecycleMonitor.notifyAll();
            }
        }
    }

    @Override
    public void abortTransaction(int txId, String reason) {
        Transaction tx = requireTransaction(txId);
        synchronized (tx) {
            if (tx.getState() == TransactionState.COMMITTED || tx.getState() == TransactionState.ABORTED) {
                return;
            }
            if (tx.getState() == TransactionState.FAILED && isFailed()) {
                throw new IllegalStateException("Transaction " + txId + " is in doubt, engine failed: " + failureReason);
            }
            if (tx.getState() != TransactionState.FAILED) tx.setState(TransactionState.FAILED);
            failAndAbort(tx, reason);
        }
    }

    @Override
    public AccessStatus requestRead(int txId, DataItem data) {
        return request(txId, data, false);
    }

   @Override
    public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
        return requestWriteWithContext(txId, data, oldVal, newVal).getStatus();
    }

    @Override
    public WriteContext requestWriteWithContext(int txId, DataItem data, Object oldVal, Object newVal) {
        Objects.requireNonNull(data, "data");
        Transaction tx = activeTransactions.get(txId);
        if (tx == null || tx.getState() != TransactionState.ACTIVE) {
            return WriteContext.denied(txId, AccessStatus.ABORTED, data.tableName(), data.rowId());
        }
        synchronized (tx) {
            AccessStatus status = request(txId, data, true);
            if (status != AccessStatus.GRANTED) {
                return WriteContext.denied(txId, status, data.tableName(), data.rowId());
            }
            long lsn = 0L;
            if (frm != null && (oldVal != null || newVal != null)) {
                try {
                    lsn = persistDataLog(txId, data, oldVal, newVal);
                } catch (RuntimeException failure) {
                    if (activeTransactions.containsKey(txId)) {
                        failAndAbort(tx, "Unable to persist data log");
                    }
                    throw failure;
                }

            }
            return new WriteContext(txId, AccessStatus.GRANTED, lsn, data.tableName(), data.rowId());
        }

    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data) {
        return request(txId, data, true);
    }

    /**
     * Acquires both row identities and writes the relocation log before the
     * caller is allowed to change storage.
     */
    public AccessStatus requestRelocation(
            int txId,
            DataItem oldItem,
            DataItem newItem,
            Object oldValues,
            Object newValues) {
        Objects.requireNonNull(oldItem, "oldItem");
        Objects.requireNonNull(newItem, "newItem");
        Transaction tx = activeTransactions.get(txId);
        if (tx == null) return AccessStatus.ABORTED;
        synchronized (tx) {
            if (tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;
            return prepareRelocationLocked(tx, oldItem, newItem, oldValues, newValues);
        }
    }

    /**
     * Runs a relocation as one CCM operation. Holding the transaction monitor
     * across the callback prevents commit/abort from releasing protection
     * between the WAL preparation and the physical move.
     */
    @Override
    public <T> T executeRelocation(
            int txId,
            DataItem oldItem,
            DataItem newItem,
            Object oldValues,
            Object newValues,
            Supplier<T> physicalChange) {
        Objects.requireNonNull(oldItem, "oldItem");
        Objects.requireNonNull(newItem, "newItem");
        Objects.requireNonNull(physicalChange, "physicalChange");
        Transaction tx = requireTransaction(txId);

        for (;;) {
            synchronized (tx) {
                if (tx.getState() != TransactionState.ACTIVE) {
                    throw new IllegalStateException("Transaction " + txId + " is " + tx.getState());
                }
                AccessStatus status = prepareRelocationLocked(
                        tx, oldItem, newItem, oldValues, newValues);
                if (status == AccessStatus.WAITING) {
                    // Release the transaction monitor while waiting so the
                    // other transaction can commit and release its locks.
                } else if (status == AccessStatus.ABORTED) {
                    throw new IllegalStateException("Relocation was aborted for transaction " + txId);
                } else {
                    try {
                        return physicalChange.get();
                    } catch (RuntimeException failure) {
                        if (activeTransactions.containsKey(txId)) {
                            failAndAbort(tx, "Relocation failed");
                        }
                        throw failure;
                    }
                }
            }
            LockSupport.parkNanos(10_000_000L);
            if (stopping || Thread.currentThread().isInterrupted()) {
                abortTransaction(txId, "Relocation cancelled");
                throw new IllegalStateException("Relocation cancelled for transaction " + txId);
            }
        }
    }

    @Override
    public Transaction getTransaction(int txId) { return activeTransactions.get(txId); }

    public Map<Integer, Transaction> getActiveTransactions() {
        return Collections.unmodifiableMap(activeTransactions);
    }

    public boolean verifySchedule(List<Operation> manualSchedule) {
        return new ScheduleVerifier(manualSchedule).isSerializable();
    }
    
    private AccessStatus request(int txId, DataItem data, boolean write) {
        Objects.requireNonNull(data, "data");
        Transaction tx = activeTransactions.get(txId);
        if (tx == null || tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;
        AccessStatus result;
        synchronized (tx) {
            if (tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;
            try {
                result = write ? protocol.requestWrite(tx, data) : protocol.requestRead(tx, data);
            } catch (RuntimeException failure) {
                failAndAbort(tx, "Protocol access failed");
                throw failure;
            }
            if (result == null) {
                failAndAbort(tx, "Protocol returned no access decision");
                return AccessStatus.ABORTED;
            }
            if (result == AccessStatus.ABORTED) {
                if (tx.getState() == TransactionState.ACTIVE) failAndAbort(tx, "Protocol aborted access");
            }
        }
        return result;
    }

    private boolean persistCommitLog(int txId) {
        if (frm == null) return true;
        RecoveryResult result = frm.writeTransactionLog(txId, LogType.COMMIT);
        return result != null && result.isSuccess() && frm.flushLogUpTo(result.getLsn());
    }

    private AccessStatus prepareRelocationLocked(
            Transaction tx,
            DataItem oldItem,
            DataItem newItem,
            Object oldValues,
            Object newValues) {
        if (oldItem.equals(newItem)) {
            AccessStatus status = protocol.requestWrite(tx, oldItem);
            if (status == AccessStatus.ABORTED && tx.getState() == TransactionState.ACTIVE) {
                failAndAbort(tx, "Protocol aborted relocation");
                return status;
            }
            if (status != AccessStatus.GRANTED || frm == null) return status;
            try {
                persistDataLog(tx.getId(), oldItem, oldValues, newValues);
                return status;
            } catch (RuntimeException failure) {
                failAndAbort(tx, "Unable to persist relocation log");
                throw failure;
            }
        }

        AccessStatus status = protocol.requestWriteBatch(tx, List.of(oldItem, newItem));
        if (status == AccessStatus.ABORTED && tx.getState() == TransactionState.ACTIVE) {
            failAndAbort(tx, "Protocol aborted relocation");
            return status;
        }
        if (status != AccessStatus.GRANTED || frm == null) return status;

        try {
            // record the complete relocation once, after both identities are protected and before storage is changed.
            persistRelocationLog(tx.getId(), oldItem, newItem, oldValues, newValues);
            return status;
        } catch (RuntimeException failure) {
            failAndAbort(tx, "Unable to persist relocation log");
            throw failure;
        }
    }

    private long persistDataLog(int txId, DataItem data, Object oldVal, Object newVal) {
        LogType opType = (oldVal == null) ? LogType.INSERT
                : (newVal == null ? LogType.DELETE : LogType.UPDATE);
        RecoveryResult result = frm.writeDataLog(
                txId,
                opType,
                data.tableName(),
                data.rowId(),
                toRecordImage(oldVal),
                toRecordImage(newVal));
        if (result == null || !result.isSuccess()
                || !frm.flushLogUpTo(result.getLsn())) {
            throw new IllegalStateException(
                    "Unable to persist " + opType.toString().toLowerCase()
                            + " log for transaction " + txId);
        }
        return result.getLsn();
    }

    private void persistRelocationLog(
            int txId,
            DataItem oldItem,
            DataItem newItem,
            Object oldValues,
            Object newValues) {
        RecoveryResult result = frm.writeRelocationLog(
                txId,
                oldItem.tableName(),
                oldItem.rowId(),
                newItem.rowId(),
                toRecordImage(oldValues),
                toRecordImage(newValues));
        if (result == null || !result.isSuccess()
                || !frm.flushLogUpTo(result.getLsn())) {
            throw new IllegalStateException(
                    "Unable to persist relocation log for transaction " + txId);
        }
    }

    private void failAndAbort(Transaction tx, String reason) {
        failAndAbort(tx, reason, null);
    }

    // rollback dulu baru lepas akses, kalau rollback gagal lock tetap dipegang dan engine masuk failed state
    // cause dipakai buat nyimpen error asli biar ga ketimpa error rollback
    private void failAndAbort(Transaction tx, String reason, RuntimeException cause) {
        synchronized (tx) {
            if (tx.getState() != TransactionState.FAILED) tx.setState(TransactionState.FAILED);
            RuntimeException rollbackFailure = null;
            if (frm != null) {
                RecoveryResult rollback;
                RuntimeException rollbackError = null;
                try {
                    rollback = frm.rollbackTransaction(tx.getId());
                } catch (RuntimeException e) {
                    rollback = null;
                    rollbackError = e;
                }
                if (rollback == null || !rollback.isSuccess()) {
                    String detail = rollback != null ? rollback.getMessage()
                            : rollbackError != null ? rollbackError.getMessage() : "no result";
                    enterFailedState("Rollback failed for transaction " + tx.getId() + " after " + reason + ", " + detail);
                    IllegalStateException failure = new IllegalStateException(
                            "Rollback failed for transaction " + tx.getId() + ", engine moved to failed state", rollbackError);
                    if (cause != null) {
                        cause.addSuppressed(failure);
                        throw cause;
                    }
                    throw failure;
                }
            }
            protocol.releaseResources(tx);
            tx.setState(TransactionState.ABORTED);
            activeTransactions.remove(tx.getId(), tx);
            synchronized (lifecycleMonitor) {
                lifecycleMonitor.notifyAll();
            }
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }
    }

    // tandai engine failed, transaksi baru ditolak dan yang lagi nunggu checkpoint dibangunin
    private void enterFailedState(String reason) {
        synchronized (lifecycleMonitor) {
            if (failureReason == null) failureReason = reason;
            lifecycleMonitor.notifyAll();
        }
    }

    private void requireNotFailed() {
        if (failureReason != null) {
            throw new IllegalStateException("Engine is in failed state, restart required: " + failureReason);
        }
    }

    private void abortFromProtocol(Transaction tx) {
        if (activeTransactions.containsKey(tx.getId())) {
            failAndAbort(tx, "Deadlock victim");
        }
    }

    private void configureProtocol(ConcurrencyProtocol protocol) {
        if (protocol instanceof LockBasedProtocol lockBasedProtocol) {
            lockBasedProtocol.setAbortHandler(this::abortFromProtocol);
            lockBasedProtocol.setTransactionResolver(activeTransactions::get);
        }
    }

    private Transaction requireTransaction(int txId) {
        Transaction tx = activeTransactions.get(txId);
        if (tx == null) throw new IllegalArgumentException("Unknown transaction: " + txId);
        return tx;
    }

    private static void requireState(Transaction tx, TransactionState expected) {
        if (tx.getState() != expected) {
            throw new IllegalStateException("Transaction " + tx.getId() + " is " + tx.getState()
                    + ", expected " + expected);
        }
    }

    private static RecordImage toRecordImage(Object val) {
        if (val == null) return null;
        if (val instanceof RecordImage img) return img;
        if (val instanceof List<?> list) return new RecordImage(new ArrayList<Object>(list));
        return new RecordImage(List.of(val));
    }
}
