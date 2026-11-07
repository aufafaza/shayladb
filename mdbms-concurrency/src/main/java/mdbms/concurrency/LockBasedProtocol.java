package mdbms.concurrency;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;

/** Lock-based concurrency strategy used by the CC1 facade. */
public class LockBasedProtocol implements ConcurrencyProtocol {
    private final LockManager lockManager;
    private final DeadlockHandler deadlockHandler;
    private Consumer<Transaction> abortHandler;
    private IntFunction<Transaction> transactionResolver;

    public LockBasedProtocol() {
        this(new LockManager(), new DeadlockHandler());
    }

    public LockBasedProtocol(LockManager lockManager, DeadlockHandler deadlockHandler) {
        this.lockManager = java.util.Objects.requireNonNull(lockManager, "lockManager");
        this.deadlockHandler = java.util.Objects.requireNonNull(deadlockHandler, "deadlockHandler");
    }

    void setAbortHandler(Consumer<Transaction> abortHandler) {
        this.abortHandler = Objects.requireNonNull(abortHandler, "Abort handler is required");
    }

    void setTransactionResolver(IntFunction<Transaction> transactionResolver) {
        this.transactionResolver = Objects.requireNonNull(
                transactionResolver, "Transaction resolver is required");
    }
    
    @Override 
    public AccessStatus requestRead(Transaction tx, DataItem data) {
        return lockManager.acquireLock(tx, data, Locktype.SHARED);
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
        if (transactionResolver == null) return AccessStatus.ABORTED;
        Transaction tx = transactionResolver.apply(txId);
        if (tx == null) return AccessStatus.ABORTED;
        return requestWrite(tx, data);
    }

    @Override 
    public void releaseResources(Transaction tx) {
        lockManager.releaseAllLocks(tx);
    }

    private AccessStatus handleResult(Transaction tx, AccessStatus result) {
        if (result != AccessStatus.WAITING || !deadlockHandler.detectDeadlock()) {
            return result;
        }
        Transaction victim = deadlockHandler.chooseVictim();
        if (victim == null) return result;
        abortVictim(victim);
        if (victim == tx || tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;
        return result;
    }

    private void abortVictim(Transaction victim) {
        if (abortHandler != null) {
            abortHandler.accept(victim);
            return;
        }

        if (victim.getState() == TransactionState.ACTIVE) {
            victim.setState(TransactionState.FAILED);
            lockManager.releaseAllLocks(victim);
            victim.setState(TransactionState.ABORTED);
        } else {
            lockManager.releaseAllLocks(victim);
        }
    }
}
