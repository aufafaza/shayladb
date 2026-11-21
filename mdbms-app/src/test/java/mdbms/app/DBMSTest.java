package mdbms.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataRetrieval;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DBMSTest {

    private final List<String> calls = new ArrayList<>();
    private boolean recoverySucceeds;
    private boolean checkpointSucceeds;
    private boolean missingRecoveryResult;
    private boolean closeFails;
    private boolean rollbackFails;
    private java.util.concurrent.CountDownLatch checkpointEntered;
    private java.util.concurrent.CountDownLatch releaseCheckpoint;
    private final java.util.concurrent.CountDownLatch storageClosed = new java.util.concurrent.CountDownLatch(1);
    private int recoveredMaxTxId;
    private DBMSConfig cfg;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        recoverySucceeds = true;
        checkpointSucceeds = true;
        cfg = new DBMSConfig(dir.toString(), 8, ConcurrencyAlgorithm.TIMESTAMP_BASED);
    }

    private DBMS withStubs(DBMSConfig config) {
        IDurableStorage storage = (IDurableStorage) Proxy.newProxyInstance(
                IDurableStorage.class.getClassLoader(),
                new Class<?>[] {IDurableStorage.class, IDataAccess.class, ISchemaCatalog.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("listTable")) return List.of("fixture");
                    calls.add(method.getName());
                    if (method.getName().equals("close")) {
                        storageClosed.countDown();
                        if (closeFails) throw new IllegalStateException("flush failed");
                    }
                    return null;
                });
        IFailureRecoveryManager frm = (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals("createCheckpoint") && checkpointEntered != null) {
                        checkpointEntered.countDown();
                        releaseCheckpoint.await();
                    }
                    return switch (method.getName()) {
                        case "recoverOnStartup" -> missingRecoveryResult ? null
                                : new RecoveryResult(recoverySucceeds, 1L, recoveredMaxTxId, "stub");
                        case "createCheckpoint" -> new RecoveryResult(checkpointSucceeds, 1L, recoveredMaxTxId, "stub");
                        case "rollbackTransaction" -> new RecoveryResult(!rollbackFails, 1L, recoveredMaxTxId, "rollback stub");
                        case "flushLogUpTo" -> true;
                        default -> new RecoveryResult(true, 1L, recoveredMaxTxId, "stub");
                    };
                });
        return new DBMS(config, storage, frm);
    }

    @Test
    void isRunningFollowsLifecycle() {
        DBMS dbms = new DBMS(cfg);
        assertFalse(dbms.isRunning());

        dbms.start();
        assertTrue(dbms.isRunning());

        dbms.shutdown();
        assertFalse(dbms.isRunning());
    }

    @Test
    void startRunsRecoveryOnce() {
        DBMS dbms = withStubs(cfg);

        dbms.start();
        dbms.start();

        assertEquals(List.of("recoverOnStartup"), calls);
    }

    @Test
    void failedRecoveryStopsStartup() {
        recoverySucceeds = false;
        DBMS dbms = withStubs(cfg);

        assertThrows(IllegalStateException.class, dbms::start);
        assertFalse(dbms.isRunning());
        assertNull(dbms.getQueryProcessor());
        assertNull(dbms.getConcurrencyControl());
    }

    @Test
    void missingRecoveryResultStopsStartup() {
        missingRecoveryResult = true;
        DBMS dbms = withStubs(cfg);

        assertThrows(IllegalStateException.class, dbms::start);
        assertFalse(dbms.isRunning());
        assertNull(dbms.getQueryProcessor());
        assertNull(dbms.getConcurrencyControl());
    }

    @Test
    void recoveryInitializesTransactionIdsWithoutRunningTwice() {
        recoveredMaxTxId = 42;
        DBMS dbms = withStubs(cfg);
        dbms.start();

        int tx = dbms.getConcurrencyControl().beginTransaction();
        assertEquals(43, tx);
        dbms.getConcurrencyControl().commitTransaction(tx);
        assertEquals(1, calls.stream().filter("recoverOnStartup"::equals).count());
        dbms.shutdown();
    }

    @Test
    void failedCheckpointDoesNotReportSuccessfulShutdown() {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        checkpointSucceeds = false;
        calls.clear();

        assertThrows(IllegalStateException.class, dbms::shutdown);
        assertEquals(List.of("createCheckpoint"), calls);
        assertFalse(dbms.isRunning());
        assertThrows(IllegalStateException.class, dbms::shutdown);
    }

    @Test
    void shutdownCheckpointsBeforeFlushing() {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        calls.clear();

        dbms.shutdown();

        assertEquals(List.of("createCheckpoint", "close"), calls);
    }

    @Test
    void shutdownTwiceOnlyRunsOnce() {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        calls.clear();

        dbms.shutdown();
        dbms.shutdown();

        assertEquals(List.of("createCheckpoint", "close"), calls);
    }

    @Test
    void shutdownBeforeStartDoesNothing() {
        DBMS dbms = withStubs(cfg);

        assertDoesNotThrow(() -> { dbms.shutdown(); });
        assertTrue(calls.isEmpty());
    }

    @Test
    void flushFailureClosesAdmissionAndRemainsVisibleOnRepeatedShutdown() {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        var concurrency = dbms.getConcurrencyControl();
        closeFails = true;
        assertThrows(IllegalStateException.class, dbms::shutdown);
        assertFalse(dbms.isRunning());
        assertNull(dbms.getQueryProcessor());
        assertThrows(IllegalStateException.class, concurrency::beginTransaction);
        assertThrows(IllegalStateException.class, dbms::shutdown);
    }

    @Test
    void rollbackFailurePreventsCheckpointAndSuccessfulShutdown() {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        dbms.getConcurrencyControl().beginTransaction();
        rollbackFails = true;
        calls.clear();
        assertThrows(IllegalStateException.class, dbms::shutdown);
        assertFalse(calls.contains("createCheckpoint"));
        assertFalse(calls.contains("close"));
        assertFalse(dbms.isRunning());
        assertThrows(IllegalStateException.class, dbms::shutdown);
    }

    @Test
    void timeoutIsVisibleWhileCheckpointIoFinishesWithoutBeingInterrupted() throws Exception {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        checkpointEntered = new java.util.concurrent.CountDownLatch(1);
        releaseCheckpoint = new java.util.concurrent.CountDownLatch(1);
        dbms.startCheckpointScheduler(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            assertTrue(checkpointEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> dbms.shutdown(50, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertTrue(failure.getMessage().contains("timed out"));
            assertFalse(dbms.isRunning());
        } finally {
            releaseCheckpoint.countDown();
        }
        assertTrue(storageClosed.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, dbms::shutdown);
    }

    @Test
    void validationBasedAlgorithmGivesClearError() {
        ConcurrencyAlgorithm algorithm = ConcurrencyAlgorithm.VALIDATION_BASED;
        DBMS dbms = withStubs(new DBMSConfig("dir", 8, algorithm));

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, dbms::start);

        assertTrue(error.getMessage().contains(algorithm.name()));
        assertFalse(dbms.isRunning());
    }

    // SM14 database contoh kebentuk pas start pertama dan udah ada di disk tanpa nunggu shutdown
    @Test
    void firstStartLoadsSampleDatabase() {
        DBMS dbms = new DBMS(cfg);
        dbms.start();
        dbms.shutdown();

        StorageManager reopened = new StorageManager(cfg.getDataDirectory(), 8, 4);
        assertEquals(List.of("students", "takes", "courses"), reopened.listTable());
        for (String table : reopened.listTable()) {
            assertEquals(50, reopened.readBlock(new DataRetrieval(table, List.of(), List.of())).rows().size());
        }
    }

    // start berikutnya ga nambah baris dobel
    @Test
    void laterStartsKeepExistingData() {
        DBMS first = new DBMS(cfg);
        first.start();
        first.shutdown();
        DBMS second = new DBMS(cfg);
        second.start();
        second.shutdown();

        StorageManager reopened = new StorageManager(cfg.getDataDirectory(), 8, 4);
        assertEquals(50, reopened.readBlock(new DataRetrieval("students", List.of(), List.of())).rows().size());
    }

    // data directory yang udah di bootstrap schema sendiri ga diisi data contoh
    @Test
    void startSkipsSampleWhenSchemaExists() {
        StorageManager storage = new StorageManager(cfg.getDataDirectory(), 8, 4);
        storage.updateTableSchema(new TableSchema("custom", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false)), List.of("id"), List.of()));

        DBMS dbms = new DBMS(cfg);
        dbms.start();
        dbms.shutdown();

        assertEquals(List.of("custom"), new StorageManager(cfg.getDataDirectory(), 8, 4).listTable());
    }

    @Test
    void startCreatesConcurrencyControl() {
        DBMS dbms = new DBMS(cfg);
        dbms.start();

        assertNotNull(dbms.getConcurrencyControl());
        assertNotNull(dbms.getQueryProcessor());
        dbms.shutdown();
    }
}
