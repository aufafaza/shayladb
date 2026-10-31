package mdbms.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.RecoveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DBMSTest {

    private final List<String> calls = new ArrayList<>();
    private boolean recoverySucceeds;
    private DBMSConfig cfg;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        recoverySucceeds = true;
        cfg = new DBMSConfig(dir.toString(), 8, ConcurrencyAlgorithm.TIMESTAMP_BASED);
    }

    private DBMS withStubs(DBMSConfig config) {
        IDurableStorage storage = (IDurableStorage) Proxy.newProxyInstance(
                IDurableStorage.class.getClassLoader(),
                new Class<?>[] {IDurableStorage.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return null;
                });
        IFailureRecoveryManager frm = (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return new RecoveryResult(recoverySucceeds, 1L, 0, "stub");
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
    }

    @Test
    void shutdownCheckpointsBeforeFlushing() {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        calls.clear();

        dbms.shutdown();

        assertEquals(List.of("createCheckpoint", "flushBuffer"), calls);
    }

    @Test
    void shutdownTwiceOnlyRunsOnce() {
        DBMS dbms = withStubs(cfg);
        dbms.start();
        calls.clear();

        dbms.shutdown();
        dbms.shutdown();

        assertEquals(List.of("createCheckpoint", "flushBuffer"), calls);
    }

    @Test
    void shutdownBeforeStartDoesNothing() {
        DBMS dbms = withStubs(cfg);

        assertDoesNotThrow(dbms::shutdown);
        assertTrue(calls.isEmpty());
    }

    @Test
    void unimplementedAlgorithmsGiveClearError() {
        for (ConcurrencyAlgorithm algorithm : List.of(ConcurrencyAlgorithm.LOCK_BASED, ConcurrencyAlgorithm.VALIDATION_BASED)) {
            DBMS dbms = withStubs(new DBMSConfig("dir", 8, algorithm));

            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, dbms::start);

            assertTrue(error.getMessage().contains(algorithm.name()));
            assertFalse(dbms.isRunning());
        }
    }

    @Test
    void startCreatesConcurrencyControl() {
        DBMS dbms = new DBMS(cfg);
        dbms.start();

        assertNotNull(dbms.getConcurrencyControl());
    }
}
