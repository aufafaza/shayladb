package mdbms.app;

import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.recovery.FailureRecoveryManager;
import mdbms.storage.StorageManager;

public class DBMS {
    private IDurableStorage storage; 
    private IFailureRecoveryManager frm; 
    // private final IQueryProcessor processor; 
    private DBMSConfig cfg; 
    private boolean running; 



    public DBMS(DBMSConfig cfg){ 
        // this.processor = processor;  
        this.cfg = cfg;
    }

    public void start(){
        StorageManager storage = new StorageManager(null, 0, 0);
        FailureRecoveryManager frm = new FailureRecoveryManager(
        );
        this.frm = frm; 
        this.storage = storage; 
        storage.attachRecoveryManager(frm);
        this.recoverOnStartup();
        running = true; 
    }

    public void shutdown(){
        if (!running) return; 
        frm.createCheckpoint(); 
        storage.flushBuffer(); 
        running = false; 
    }

    public boolean isRunning(){
        return running == true; 
    } 

    // public IQueryProcessor getQueryProcessor(){}

    private void recoverOnStartup(){} 
}
