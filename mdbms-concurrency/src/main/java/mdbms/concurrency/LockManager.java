package mdbms.concurrency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;

public class LockManager {
    private final Map<DataItem, ArrayList<Lock>> lockTable = new ConcurrentHashMap<>();
    private final Map<Transaction, PendingLock> waitingRequests = new HashMap<>();

    public synchronized AccessStatus acquireLock(Transaction tx, DataItem data, Locktype type) {
        if (data == null) throw new IllegalArgumentException("Transaction, data, and lock type are required");
        return acquireLocks(tx, List.of(data), type);
    }

    /**
     * Acquires locks for a multi-item operation atomically. The preflight pass
     * is important: a relocation must never hold the old-row lock while still
     * waiting to protect the destination row.
     */
    public synchronized AccessStatus acquireLocks(
            Transaction tx, List<DataItem> dataItems, Locktype type) {
        if (tx == null || dataItems == null || type == null) {
            throw new IllegalArgumentException("Transaction, data, and lock type are required");
        }
        if (tx.getState() != TransactionState.ACTIVE) return AccessStatus.ABORTED;

        if (dataItems == null || dataItems.isEmpty()) {
            throw new IllegalArgumentException("At least one data item is required");
        }

        List<DataItem> uniqueItems = dataItems.stream().distinct().toList();
        DataItem blockedItem = null;
        for (DataItem data : uniqueItems) {
            if (data == null) throw new IllegalArgumentException("Data items must not contain null");
            ArrayList<Lock> locks = lockTable.get(data);
            if (!isCompatible(locks, tx, type)) {
                blockedItem = data;
                break;
            }
        }
        if (blockedItem != null) {
            waitingRequests.put(tx, new PendingLock(blockedItem, type));
            return AccessStatus.WAITING;
        }

        for (DataItem data : uniqueItems) {
            ArrayList<Lock> locks = lockTable.computeIfAbsent(data, ignored -> new ArrayList<>());
            Lock owned = findLock(locks, tx);
            if (owned == null) {
                locks.add(new Lock(tx, data, type));
            } else if (type == Locktype.EXCLUSIVE) {
                owned.setLocktype(Locktype.EXCLUSIVE);
            }
        }
        waitingRequests.remove(tx);
        return AccessStatus.GRANTED;
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
