package mdbms.concurrency;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.Operation;
import mdbms.models.AccessStatus;
import mdbms.models.DataItem;
import mdbms.models.LogType;
import mdbms.models.RecoveryResult;
import mdbms.models.Transaction;
import mdbms.models.TransactionState;
import mdbms.models.log.RecordImage;

/**
 * CC1 transaction manager and facade. It owns transaction lifecycle and
 * delegates access decisions to the selected protocol strategy.
 */
public final class ConcurrencyControlManager implements IConcurrencyControl {
    private final AtomicInteger transactionCounter = new AtomicInteger(1);
    private final Map<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private final IFailureRecoveryManager frm;
    private final Object lifecycleMonitor = new Object();
    private volatile ConcurrencyProtocol protocol;
    private volatile boolean isRecovered = true;
    private boolean acceptingTransactions = true;

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

    /** Prevents new transactions from starting; existing transactions may finish. */
    public void pauseTransactions() {
        synchronized (lifecycleMonitor) {
            acceptingTransactions = false;
        }
    }

    /** Allows new transactions after a checkpoint or maintenance operation. */
    public void resumeTransactions() {
        synchronized (lifecycleMonitor) {
            acceptingTransactions = true;
            lifecycleMonitor.notifyAll();
        }
    }

    /** Blocks until all transactions have completed while the barrier is active. */
    public void awaitNoActiveTransactions() throws InterruptedException {
        synchronized (lifecycleMonitor) {
            while (!activeTransactions.isEmpty()) lifecycleMonitor.wait();
        }
    }

    public int getActiveTransactionCount() { return activeTransactions.size(); }

    public boolean isAcceptingTransactions() {
        synchronized (lifecycleMonitor) {
            return acceptingTransactions;
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
            if (!isRecovered) throw new IllegalStateException("Recovery has not completed");
            if (!acceptingTransactions) throw new IllegalStateException("Transaction admission is paused");
            int id = transactionCounter.getAndIncrement();
            Transaction tx = new Transaction(id, System.currentTimeMillis());
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
            boolean logged;
            try {
                logged = persistCommitLog(txId);
            } catch (RuntimeException failure) {
                failAndAbort(tx, "Unable to persist commit log");
                throw failure;
            }
            if (!logged) {
                failAndAbort(tx, "Unable to persist commit log");
                throw new IllegalStateException("Unable to persist commit log for transaction " + txId);
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
        AccessStatus status = request(txId, data, true);
        if (status == AccessStatus.GRANTED && frm != null && (oldVal != null || newVal != null)) {
            LogType opType = (oldVal == null) ? LogType.INSERT : (newVal == null ? LogType.DELETE : LogType.UPDATE);
            RecordImage beforeImg = toRecordImage(oldVal);
            RecordImage afterImg = toRecordImage(newVal);
            frm.writeDataLog(txId, opType, data.tableName(), data.rowId(), beforeImg, afterImg);
        }
        return status;
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data) {
        return request(txId, data, true);
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

    private void failAndAbort(Transaction tx, String reason) {
        synchronized (tx) {
            if (tx.getState() != TransactionState.FAILED) tx.setState(TransactionState.FAILED);
            if (frm != null) {
                RecoveryResult rollback = frm.rollbackTransaction(tx.getId());
                if (rollback == null || !rollback.isSuccess()) {
                    throw new IllegalStateException("Rollback failed for transaction " + tx.getId());
                }
            }
            protocol.releaseResources(tx);
            tx.setState(TransactionState.ABORTED);
            activeTransactions.remove(tx.getId(), tx);
            synchronized (lifecycleMonitor) {
                lifecycleMonitor.notifyAll();
            }
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
        if (val instanceof List<?> list) return new RecordImage((List<Object>) list);
        return new RecordImage(List.of(val));
    }
}
