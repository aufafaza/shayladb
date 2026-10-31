package mdbms.concurrency;

import mdbms.models.AccessStatus;

public class LockBasedProtocol {
    private LockManager lockManager;
    private DeadlockHandler deadlockHandler;
    
    AccessStatus requestRead() {
        // TODO
        return AccessStatus.GRANTED;
    } 
    
    AccessStatus requestWrite() {
        // TODO
        return AccessStatus.GRANTED;
    }

    void releaseResources() {
        // TODO
    }
    
}