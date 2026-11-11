package mdbms.concurrency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;

public class LockManager {
    private final Map<DataItem, ArrayList<Lock>> lockTable = new HashMap<>();
    private final Map<Transaction, PendingLock> waitingRequests = new HashMap<>();

    public synchronized AccessStatus acquireLock(Transaction tx, DataItem data, Locktype type) {
        if (tx == null || data == null || type == null) {
            throw new IllegalArgumentException("Transaction, data, and lock type are required");
        }
        if (tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;

        ArrayList<Lock> locks = lockTable.get(data);
        Lock owned = findLock(locks, tx);
        if (owned != null) {
            if (owned.getLocktype() == Locktype.EXCLUSIVE || type == Locktype.SHARED) {
                waitingRequests.remove(tx);
                return AccessStatus.GRANTED;
            }
            return upgradeLock(tx, data);
        }

        if (isCompatible(locks, tx, type)) {
            lockTable.computeIfAbsent(data, ignored -> new ArrayList<>())
                    .add(new Lock(tx, data, type));
            waitingRequests.remove(tx);
            return AccessStatus.GRANTED;
        }

        waitingRequests.put(tx, new PendingLock(data, type));
        return AccessStatus.WAITING;
    }

    public synchronized void releaseAllLocks(Transaction tx) {
        if (tx == null) return;
        Iterator<Map.Entry<DataItem, ArrayList<Lock>>> tables = lockTable.entrySet().iterator();
        while (tables.hasNext()) {
            ArrayList<Lock> locks = tables.next().getValue();
            locks.removeIf(lock -> lock.getTransaction() == tx);
            if (locks.isEmpty()) tables.remove();
        }
        waitingRequests.remove(tx);
    }

    public synchronized AccessStatus upgradeLock(Transaction tx, DataItem data) {
        if (tx == null || data == null) {
            throw new IllegalArgumentException("Transaction and data are required");
        }
        if (tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;

        ArrayList<Lock> locks = lockTable.get(data);
        Lock owned = findLock(locks, tx);
        if (owned == null) return acquireLock(tx, data, Locktype.EXCLUSIVE);
        if (owned.getLocktype() == Locktype.EXCLUSIVE) {
            waitingRequests.remove(tx);
            return AccessStatus.GRANTED;
        }

        if (locks.size() == 1) {
            owned.setLocktype(Locktype.EXCLUSIVE);
            waitingRequests.remove(tx);
            return AccessStatus.GRANTED;
        }

        waitingRequests.put(tx, new PendingLock(data, Locktype.EXCLUSIVE));
        return AccessStatus.WAITING;
    }

    synchronized Map<DataItem, ArrayList<Lock>> getLockTableSnapshot() {
        Map<DataItem, ArrayList<Lock>> snapshot = new HashMap<>();
        lockTable.forEach((data, locks) -> snapshot.put(data, new ArrayList<>(locks)));
        return snapshot;
    }

    synchronized Map<Transaction, PendingLock> getWaitingRequestsSnapshot() {
        return new HashMap<>(waitingRequests);
    }

    private static Lock findLock(ArrayList<Lock> locks, Transaction tx) {
        if (locks == null) return null;
        for (Lock lock : locks) {
            if (lock.getTransaction() == tx) return lock;
        }
        return null;
    }

    private static boolean isCompatible(ArrayList<Lock> locks, Transaction tx, Locktype requested) {
        if (locks == null) return true;
        for (Lock lock : locks) {
            if (lock.getTransaction() != tx
                    && (requested == Locktype.EXCLUSIVE
                    || lock.getLocktype() == Locktype.EXCLUSIVE)) {
                return false;
            }
        }
        return true;
    }

    static final class PendingLock {
        private final DataItem data;
        private final Locktype type;

        PendingLock(DataItem data, Locktype type) {
            this.data = data;
            this.type = type;
        }

        DataItem getData() {
            return data;
        }

        Locktype getType() {
            return type;
        }
    }
}