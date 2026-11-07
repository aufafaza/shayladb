package mdbms.concurrency;

import java.util.Objects;
import java.util.function.Consumer;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;

public class LockBasedProtocol implements ConcurrencyProtocol {
    private final LockManager lockManager;
    private final DeadlockHandler deadlockHandler;
    private Consumer<Transaction> abortHandler;

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
    
    @Override 
    public AccessStatus requestRead(Transaction tx, DataItem data) {
        return handleResult(tx, lockManager.acquireLock(tx, data, Locktype.SHARED));
    } 
    
    @Override 
    public AccessStatus requestWrite(Transaction tx, DataItem data) {
        return handleResult(tx, lockManager.acquireLock(tx, data, Locktype.EXCLUSIVE));
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
