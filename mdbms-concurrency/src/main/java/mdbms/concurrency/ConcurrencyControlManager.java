package mdbms.concurrency;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.AccessStatus;
import mdbms.models.DataItem;
import mdbms.models.LogType;
import mdbms.models.Transaction;
import mdbms.models.TransactionState;

/**
 * CC1 transaction manager and facade. It owns transaction lifecycle and
 * delegates access decisions to the selected protocol strategy.
 */
public final class ConcurrencyControlManager implements IConcurrencyControl {
    private final AtomicInteger transactionCounter = new AtomicInteger();
    private final Map<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private final IFailureRecoveryManager frm;
    private volatile ConcurrencyProtocol protocol;
    private volatile boolean isRecovered = true;

    public ConcurrencyControlManager() {
        this(GrantAllProtocol.INSTANCE, null);
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
    }

    public void setProtocol(ConcurrencyProtocol protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    @Override
    public int beginTransaction() {
        if (!isRecovered) throw new IllegalStateException("Recovery has not completed");
        int id = transactionCounter.getAndIncrement();
        Transaction tx = new Transaction(id, System.currentTimeMillis());
        activeTransactions.put(id, tx);
        return id;
    }

    @Override
    public void commitTransaction(int txId) {
        Transaction tx = requireTransaction(txId);
        synchronized (tx) {
            requireState(tx, TransactionState.ACTIVE);
            tx.setState(TransactionState.PARTIALLY_COMMITTED);
            boolean logged = frm == null || frm.writeTransactionLog(txId, LogType.COMMIT).isSuccess();
            if (!logged) {
                failAndAbort(tx, "Unable to persist commit log");
                return;
            }
            tx.setState(TransactionState.COMMITTED);
            protocol.releaseResources(tx);
            activeTransactions.remove(txId, tx);
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

    public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
        return request(txId, data, true);
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

    private AccessStatus request(int txId, DataItem data, boolean write) {
        Objects.requireNonNull(data, "data");
        Transaction tx = activeTransactions.get(txId);
        if (tx == null || tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;
        AccessStatus result = write ? protocol.requestWrite(tx, data) : protocol.requestRead(tx, data);
        if (result == AccessStatus.ABORTED) {
            synchronized (tx) {
                if (tx.getState() == TransactionState.ACTIVE) failAndAbort(tx, "Protocol aborted access");
            }
        }
        return result;
    }

    private void failAndAbort(Transaction tx, String reason) {
        if (tx.getState() != TransactionState.FAILED) tx.setState(TransactionState.FAILED);
        if (frm != null) frm.rollbackTransaction(tx.getId());
        protocol.releaseResources(tx);
        tx.setState(TransactionState.ABORTED);
        activeTransactions.remove(tx.getId(), tx);
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

    private enum GrantAllProtocol implements ConcurrencyProtocol {
        INSTANCE;
        public AccessStatus requestRead(Transaction tx, DataItem data) { return AccessStatus.GRANTED; }
        public AccessStatus requestWrite(Transaction tx, DataItem data) { return AccessStatus.GRANTED; }
        public void releaseResources(Transaction tx) { }
    }
}
