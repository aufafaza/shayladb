package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;

public class LockBasedProtocol implements ConcurrencyProtocol {
    private final LockManager lockManager;
    private final DeadlockHandler deadlockHandler;

    public LockBasedProtocol() {
        this(new LockManager());
    }

    public LockBasedProtocol(LockManager lockManager) {
        if (lockManager == null) throw new IllegalArgumentException("Lock manager is required");
        this.lockManager = lockManager;
        this.deadlockHandler = new DeadlockHandler(lockManager);
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
        lockManager.releaseAllLocks(victim);
        if (victim == tx) return AccessStatus.ABORTED;
        return result;
    }
}
