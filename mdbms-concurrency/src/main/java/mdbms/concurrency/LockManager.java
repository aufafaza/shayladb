package mdbms.concurrency;

import java.util.ArrayList;
import java.util.Map;

import mdbms.models.AccessStatus;
import mdbms.models.Transaction;
import mdbms.models.DataItem;

public class LockManager {
    private Map<DataItem, ArrayList<Lock>> lockTable;

    public AccessStatus acquireLock (Transaction tx, DataItem data, Locktype type) {
        // TODO
        return AccessStatus.GRANTED; 
    } 

    public void releaseAllLocks(Transaction tx) {
        // TODO
    }

    public AccessStatus upgradeLock(Transaction tx, DataItem data) {
        // TODO
        return AccessStatus.GRANTED;
    }

    
}