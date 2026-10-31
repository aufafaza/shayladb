package mdbms.app;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.IQueryProcessor;
import mdbms.concurrency.ConcurrencyControlManager;
import mdbms.concurrency.ConcurrencyProtocol;
import mdbms.concurrency.TimestampsBasedProtocol;
import mdbms.models.RecoveryResult;
import mdbms.recovery.FailureRecoveryManager;
import mdbms.storage.StorageManager;

public class DBMS {
    private static final int INDEX_MAX_DEGREE = 4;

    private final DBMSConfig cfg;
    private final IDurableStorage storage;
    private final IFailureRecoveryManager frm;
    private IConcurrencyControl concurrencyControl;
    private IQueryProcessor processor;
    private volatile boolean running;

    public DBMS(DBMSConfig cfg){
        this(cfg, new FailureRecoveryManager());
    }

    private DBMS(DBMSConfig cfg, IFailureRecoveryManager frm){
        this(cfg, createStorage(cfg, frm), frm);
    }

    DBMS(DBMSConfig cfg, IDurableStorage storage, IFailureRecoveryManager frm){
        this.cfg = cfg;
        this.storage = storage;
        this.frm = frm;
    }

    public synchronized void start(){
        if (running) return;
        ConcurrencyProtocol protocol = createProtocol(cfg.getConcurrencyAlgorithm());
        recoverOnStartup();
        concurrencyControl = new ConcurrencyControlManager(protocol, frm);
        // TODO: assign the real QueryProcessor here once it implements IQueryProcessor
        running = true;
    }

    public synchronized void shutdown(){
        if (!running) return;
        frm.createCheckpoint();
        storage.flushBuffer();
        running = false;
    }

    public boolean isRunning(){
        return running;
    }

    public IQueryProcessor getQueryProcessor(){
        return processor;
    }

    public IConcurrencyControl getConcurrencyControl(){
        return concurrencyControl;
    }

    private void recoverOnStartup(){
        RecoveryResult result = frm.recoverOnStartup();
        if (!result.isSuccess()) {
            throw new IllegalStateException("Recovery failed: " + result.getMessage());
        }
    }

    private static IDurableStorage createStorage(DBMSConfig cfg, IFailureRecoveryManager frm){
        StorageManager storage = new StorageManager(cfg.getDataDirectory(), cfg.getBufferCapacity(), INDEX_MAX_DEGREE);
        storage.attachRecoveryManager(frm);
        return storage;
    }

    private static ConcurrencyProtocol createProtocol(ConcurrencyAlgorithm algorithm){
        return switch (algorithm) {
            case TIMESTAMP_BASED -> new TimestampsBasedProtocol();
            case LOCK_BASED, VALIDATION_BASED -> throw new UnsupportedOperationException(
                    "Concurrency algorithm not implemented yet: " + algorithm);
        };
    }
}
