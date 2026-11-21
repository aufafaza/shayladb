package mdbms.app;

import java.nio.file.Path;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.IQueryProcessor;
import mdbms.api.ISchemaCatalog;
import mdbms.concurrency.ConcurrencyControlManager;
import mdbms.concurrency.ConcurrencyProtocol;
import mdbms.concurrency.LockBasedProtocol;
import mdbms.concurrency.TimestampsBasedProtocol;
import mdbms.models.log.RecoveryResult;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.QueryProcessor;
import mdbms.recovery.FailureRecoveryFacade;
import mdbms.recovery.WalFileHandler;
import mdbms.storage.SampleDataLoader;
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
        this(cfg, new StorageManager(cfg.getDataDirectory(), cfg.getBufferCapacity(), INDEX_MAX_DEGREE));
    }

    private DBMS(DBMSConfig cfg, StorageManager storage){
        this(cfg, storage, new FailureRecoveryFacade(
                Path.of(cfg.getDataDirectory()).resolve(WalFileHandler.DEFAULT_WAL_PATH),
                Path.of(cfg.getDataDirectory()).resolve("snapshots"), storage, storage));
    }

    DBMS(DBMSConfig cfg, IDurableStorage storage, IFailureRecoveryManager frm){
        this.cfg = cfg;
        this.storage = storage;
        this.frm = frm;
        if (storage instanceof StorageManager manager) {
            manager.attachRecoveryManager(frm);
        }
    }

    public synchronized void start(){
        if (running) return;
        try {
            ConcurrencyProtocol protocol = createProtocol(cfg.getConcurrencyAlgorithm());
            ConcurrencyControlManager manager = new ConcurrencyControlManager(protocol, frm);
            // CCM owns the single recovery call and seeds its IDs from the recovered WAL.
            manager.initAfterRecovery();
            loadSampleData();
            IQueryProcessor queryProcessor = new QueryProcessor(
                new QueryOptimizer((ISchemaCatalog) storage, new ShaylaParser(new ShaylaLexer(null))),
                (IDataAccess) storage, (ISchemaCatalog) storage, frm, manager);
            concurrencyControl = manager;
            processor = queryProcessor;
            running = true;
            System.out.println("Concurrency protocol: " + manager.getActiveProtocolName());
        } catch (RuntimeException | Error failure) {
            closeRecovery();
            throw failure;
        }
    }

    public synchronized void shutdown(){
        if (!running) {
            closeRecovery();
            return;
        }
        RecoveryResult checkpoint = frm.createCheckpoint();
        if (checkpoint == null || !checkpoint.isSuccess()) {
            throw new IllegalStateException("Checkpoint failed during shutdown: "
                    + (checkpoint == null ? "no recovery result" : checkpoint.getMessage()));
        }
        storage.flushBuffer();
        closeRecovery();
        running = false;
        processor = null;
        concurrencyControl = null;
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

    private void closeRecovery(){
        if (frm instanceof FailureRecoveryFacade recovery) {
            recovery.close();
        }
    }

    // isi database contoh SM14 kalau katalog masih kosong, dijalanin setelah recovery dan sebelum transaksi diterima
    // langsung di flush biar data contoh udah aman di disk walau server mati sebelum shutdown
    private void loadSampleData(){
        if (!(storage instanceof IDataAccess data) || !(storage instanceof ISchemaCatalog catalog)) return;
        if (!catalog.listTable().isEmpty()) return;
        new SampleDataLoader(data, catalog).loadIfEmpty();
        storage.flushBuffer();
    }

    private static ConcurrencyProtocol createProtocol(ConcurrencyAlgorithm algorithm){
        return switch (algorithm) {
            case TIMESTAMP_BASED -> new TimestampsBasedProtocol();
            case LOCK_BASED -> new LockBasedProtocol();
            case VALIDATION_BASED -> throw new UnsupportedOperationException(
                    "Concurrency algorithm not implemented yet: " + algorithm);
        };
    }
}
