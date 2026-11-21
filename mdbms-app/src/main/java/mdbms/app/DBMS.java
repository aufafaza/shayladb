package mdbms.app;

import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
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
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.QueryProcessor;
import mdbms.recovery.FailureRecoveryFacade;
import mdbms.recovery.WalFileHandler;
import mdbms.storage.SampleDataLoader;
import mdbms.storage.StorageManager;
import mdbms.net.DBMSServer;

public class DBMS {
    private static final int INDEX_MAX_DEGREE = 4;
    public static final long SHUTDOWN_TIMEOUT_SECONDS = 15;
    private final Set<DBMSServer> servers = ConcurrentHashMap.newKeySet();
    private final Phaser executions = new Phaser(1);
    private final ReentrantLock maintenance = new ReentrantLock();
    private volatile ScheduledExecutorService checkpointScheduler;
    private volatile boolean shutdownRequested;
    private volatile Throwable shutdownFailure;
    private CompletableFuture<Void> shutdownCompletion;
    private Thread shutdownThread;

    private final DBMSConfig cfg;
    private final IDurableStorage storage;
    private final IFailureRecoveryManager frm;
    private volatile IConcurrencyControl concurrencyControl;
    private volatile IQueryProcessor processor;
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
        if (shutdownRequested) throw new IllegalStateException("Engine is closed; create a new DBMS instance");
        if (running) return;
        try {
            ConcurrencyProtocol protocol = createProtocol(cfg.getConcurrencyAlgorithm());
            ConcurrencyControlManager manager = new ConcurrencyControlManager(protocol, frm);
            // CCM owns the single recovery call and seeds its IDs from the recovered WAL.
            manager.initAfterRecovery();
            loadSampleData();
            IQueryProcessor queryProcessor = new QueryProcessor(
                new QueryOptimizer((ISchemaCatalog) storage, new ShaylaParser()),
                (IDataAccess) storage, (ISchemaCatalog) storage, frm, manager);
            concurrencyControl = manager;
            processor = (query, txId) -> {
                synchronized (DBMS.this) {
                    if (shutdownRequested) throw new IllegalStateException("Engine is shutting down");
                    executions.register();
                }
                try {
                    return queryProcessor.executeQuery(query, txId);
                } finally {
                    executions.arriveAndDeregister();
                }
            };
            running = true;
            System.out.println("Concurrency protocol: " + manager.getActiveProtocolName());
        } catch (RuntimeException | Error failure) {
            closeRecovery();
            throw failure;
        }
    }

    public void shutdown(){
        shutdown(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public void shutdown(long timeout, TimeUnit unit) {
        if (timeout <= 0) throw new IllegalArgumentException("Shutdown timeout must be positive");
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (shutdownCompletion == null) {
                shutdownRequested = true;
                if (concurrencyControl instanceof ConcurrencyControlManager manager) manager.stopTransactions();
                shutdownCompletion = new CompletableFuture<>();
                boolean wasRunning = running;
                running = false;
                processor = null;
                shutdownThread = new Thread(() -> finishShutdown(wasRunning), "mdbms-shutdown-coordinator");
                shutdownThread.setDaemon(true);
                shutdownThread.start();
            }
            completion = shutdownCompletion;
        }
        try {
            completion.get(timeout, unit);
            if (shutdownFailure != null) throw new IllegalStateException("Shutdown failed", shutdownFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting shutdown", interrupted);
        } catch (TimeoutException timedOut) {
            shutdownFailure = timedOut;
            throw new IllegalStateException("Shutdown timed out; resources may still be in use", timedOut);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Shutdown failed", failure.getCause());
        }
    }

    public synchronized void registerServer(DBMSServer server) {
        if (!running || shutdownRequested) throw new IllegalStateException("Engine is not accepting servers");
        servers.add(java.util.Objects.requireNonNull(server));
    }

    public void checkpoint() {
        try {
            maintenance.lockInterruptibly();
            try {
                if (!running || shutdownRequested) throw new IllegalStateException("Engine is not running");
                ConcurrencyControlManager manager = (ConcurrencyControlManager) concurrencyControl;
                manager.pauseTransactions();
                try {
                    manager.awaitNoActiveTransactions();
                    requireCheckpoint();
                } finally {
                    if (!shutdownRequested && !manager.isFailed()) manager.resumeTransactions();
                }
            } finally {
                maintenance.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Checkpoint cancelled", interrupted);
        }
    }

    public synchronized void startCheckpointScheduler(long interval, TimeUnit unit) {
        if (!running || shutdownRequested) throw new IllegalStateException("Engine is not running");
        if (interval <= 0) throw new IllegalArgumentException("Checkpoint interval must be positive");
        if (checkpointScheduler != null) throw new IllegalStateException("Checkpoint scheduler already started");
        checkpointScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mdbms-checkpoint");
            thread.setDaemon(true);
            return thread;
        });
        checkpointScheduler.scheduleWithFixedDelay(() -> {
            try { checkpoint(); }
            catch (RuntimeException failure) {
                if (!shutdownRequested) {
                    shutdownFailure = failure;
                    ((ConcurrencyControlManager) concurrencyControl).pauseTransactions();
                }
            }
        }, interval, interval, unit);
    }

    private void finishShutdown(boolean wasRunning) {
        boolean quiescent = !wasRunning;
        Throwable failure = null;
        try {
            Throwable serverFailure = null;
            for (DBMSServer server : servers) {
                try { server.stopAccepting(); }
                catch (Exception problem) {
                    if (serverFailure == null) serverFailure = problem;
                    else serverFailure.addSuppressed(problem);
                }
            }
            ScheduledExecutorService scheduler = checkpointScheduler;
            if (scheduler != null) scheduler.shutdown();
            for (DBMSServer server : servers) {
                try { server.shutdown(); }
                catch (Exception problem) {
                    if (serverFailure == null) serverFailure = problem;
                    else serverFailure.addSuppressed(problem);
                }
            }
            if (serverFailure != null) throw new IllegalStateException("Server cleanup failed", serverFailure);
            if (scheduler != null && !scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Checkpoint scheduler did not terminate");
            }
            int phase = executions.arrive();
            executions.awaitAdvanceInterruptibly(phase);
            if (wasRunning) {
                maintenance.lockInterruptibly();
                try {
                    quiescent = true;
                    ConcurrencyControlManager manager = (ConcurrencyControlManager) concurrencyControl;
                    manager.awaitAdmissionStopped();
                    for (var tx : List.copyOf(manager.getActiveTransactions().values())) {
                        manager.abortTransaction(tx.getId(), "Engine shutdown");
                    }
                    if (manager.isFailed()) throw new IllegalStateException(manager.getFailureReason());
                    requireCheckpoint();
                    storage.close();
                } finally {
                    maintenance.unlock();
                }
            }
        } catch (Throwable problem) {
            failure = problem;
        } finally {
            // Never close WAL while an uncooperative worker can still mutate storage.
            if (quiescent) {
                try { closeRecovery(); }
                catch (Throwable closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            processor = null;
            if (failure == null && shutdownFailure == null) {
                concurrencyControl = null;
                shutdownCompletion.complete(null);
            }
            else {
                if (failure == null) failure = shutdownFailure;
                shutdownFailure = failure;
                shutdownCompletion.completeExceptionally(failure);
            }
        }
    }

    private void requireCheckpoint() {
        RecoveryResult result = frm.createCheckpoint();
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("Checkpoint failed: " + (result == null ? "no result" : result.getMessage()));
        }
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
