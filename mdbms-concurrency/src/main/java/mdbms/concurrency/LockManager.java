package mdbms.concurrency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;

public class LockManager {
    private final Map<DataItem, ArrayList<Lock>> lockTable = new ConcurrentHashMap<>();
    private final Map<Transaction, PendingLock> waitingRequests = new HashMap<>();

    public synchronized AccessStatus acquireLock (Transaction tx, DataItem data, Locktype type) {
        ArrayList<Lock> locks = lockTable.computeIfAbsent(data, ignored -> new ArrayList<>());
        Lock owned = findLock(locks, tx);
        if (owned != null) {
            if (owned.getLocktype() == Locktype.EXCLUSIVE || owned.getLocktype() == type) {
                waitingRequests.remove(tx);
                return AccessStatus.GRANTED;
            }
            if (type == Locktype.EXCLUSIVE && locks.size() == 1) {
                owned.setLocktype(Locktype.EXCLUSIVE);
                waitingRequests.remove(tx);
                return AccessStatus.GRANTED;
            }
        }
        for (Lock lock : locks) {
            if (lock.getTransaction().getId() != tx.getId()
                    && (type == Locktype.EXCLUSIVE || lock.getLocktype() == Locktype.EXCLUSIVE)) {
                waitingRequests.put(tx, new PendingLock(data, type));
                return AccessStatus.WAITING;
            }
        }
        if (owned == null) locks.add(new Lock(tx, data, type));
        waitingRequests.remove(tx);
        return AccessStatus.GRANTED;
    } 

    public synchronized void releaseAllLocks(Transaction tx) {
        waitingRequests.remove(tx);
        lockTable.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(lock -> lock.getTransaction().getId() == tx.getId());
            return entry.getValue().isEmpty();
        });
    }

    public synchronized AccessStatus upgradeLock(Transaction tx, DataItem data) {
        return acquireLock(tx, data, Locktype.EXCLUSIVE);
    }

    synchronized Map<DataItem, ArrayList<Lock>> getLockTableSnapshot() {
        Map<DataItem, ArrayList<Lock>> snapshot = new HashMap<>();
        lockTable.forEach((data, locks) -> snapshot.put(data, new ArrayList<>(locks)));
        return snapshot;
    }

    synchronized Map<Transaction, PendingLock> getWaitingRequestsSnapshot() {
        return new HashMap<>(waitingRequests);
    }

    static final class PendingLock {
        private final DataItem data;
        private final Locktype type;

        PendingLock(DataItem data, Locktype type) {
            this.data = data;
            this.type = type;
        }

        DataItem getData() { return data; }

        Locktype getType() { return type; }
    }

    private static Lock findLock(ArrayList<Lock> locks, Transaction tx) {
        for (Lock lock : locks) if (lock.getTransaction().getId() == tx.getId()) return lock;
        return null;
    }
}
