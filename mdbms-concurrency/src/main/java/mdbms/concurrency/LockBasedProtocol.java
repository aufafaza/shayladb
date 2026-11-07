package mdbms.concurrency;

import mdbms.models.transaction.AccessStatus;
import mdbms.models.data.DataItem;
import mdbms.models.transaction.Transaction;

/** Lock-based concurrency strategy used by the CC1 facade. */
public class LockBasedProtocol implements ConcurrencyProtocol {
    private final LockManager lockManager;
    private final DeadlockHandler deadlockHandler;

    public LockBasedProtocol() {
        this(new LockManager(), new DeadlockHandler());
    }

    public LockBasedProtocol(LockManager lockManager, DeadlockHandler deadlockHandler) {
        this.lockManager = java.util.Objects.requireNonNull(lockManager, "lockManager");
        this.deadlockHandler = java.util.Objects.requireNonNull(deadlockHandler, "deadlockHandler");
    }

    @Override
    public AccessStatus requestRead(Transaction tx, DataItem data) {
        return lockManager.acquireLock(tx, data, Locktype.SHARED);
    }

    @Override
    public AccessStatus requestWrite(Transaction tx, DataItem data) {
        return lockManager.acquireLock(tx, data, Locktype.EXCLUSIVE);
    }

    @Override
    public void releaseResources(Transaction tx) {
        lockManager.releaseAllLocks(tx);
    }
}
