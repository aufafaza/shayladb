package mdbms.concurrency;

import java.util.Objects;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;

public class LockBasedProtocol implements ConcurrencyProtocol {
    private final LockManager lockManager;
    private final DeadlockHandler deadlockHandler;
    private Consumer<Transaction> abortHandler;
    private IntFunction<Transaction> transactionResolver;

    public LockBasedProtocol() {
        this(new LockManager());
    }

    public LockBasedProtocol(LockManager lockManager) {
        if (lockManager == null) throw new IllegalArgumentException("Lock manager is required");
        this.lockManager = lockManager;
        this.deadlockHandler = new DeadlockHandler(lockManager);
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
        return handleResult(tx, lockManager.acquireLock(tx, data, Locktype.SHARED));
    } 
    
    @Override 
    public AccessStatus requestWrite(Transaction tx, DataItem data) {
        if (tx == null || data == null) {
            throw new IllegalArgumentException("Transaction and data are required");
        }
        Locktype type = data.isTableRange() ? Locktype.INTENT_EXCLUSIVE : Locktype.EXCLUSIVE;
        return handleResult(tx, lockManager.acquireLock(tx, data, type));
    }

    @Override
    public AccessStatus requestWriteBatch(Transaction tx, List<DataItem> dataItems) {
        return handleResult(tx, lockManager.acquireLocks(tx, dataItems, Locktype.EXCLUSIVE));
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

    public LockManager getLockManager() {
        return lockManager;
    }

    private AccessStatus handleResult(Transaction tx, AccessStatus result) {
        if (result != AccessStatus.WAITING) {
            return result;
        }
        while (deadlockHandler.detectDeadlock()) {
            Transaction victim = deadlockHandler.chooseVictim();
            if (victim == null) break;
            abortVictim(victim);
        }
        if (tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;
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
