package mdbms.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import mdbms.concurrency.ConcurrencyControlManager;
import mdbms.concurrency.ConcurrencyProtocol;
import mdbms.concurrency.LockBasedProtocol;
import mdbms.concurrency.TimestampsBasedProtocol;
import mdbms.models.query.ExecutionResult;
import mdbms.models.query.ParsedQuery;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.QueryProcessor;
import mdbms.processor.operator.AccessPermission;
import mdbms.processor.operator.CreateTableOperator;
import mdbms.recovery.FailureRecoveryFacade;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 25, unit = TimeUnit.SECONDS)
class TransactionConcurrencyIT {
    @TempDir
    Path directory;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private StorageManager storage;
    private FailureRecoveryFacade wal;
    private ConcurrencyControlManager ccm;
    private QueryProcessor processor;

    @BeforeEach
    void setUp() {
        AccessPermission.resetWaitTimeout();
        initEngine(new LockBasedProtocol());
    }

    @AfterEach
    void tearDown() {
        AccessPermission.resetWaitTimeout();
        executor.shutdownNow();
        if (wal != null) {
            wal.close();
        }
    }

    private void initEngine(ConcurrencyProtocol protocol) {
        if (wal != null) {
            wal.close();
        }
        Path subDir = directory.resolve(protocol.getClass().getSimpleName() + "-" + System.nanoTime());
        storage = new StorageManager(subDir.resolve("data").toString(), 16, 4);
        wal = new FailureRecoveryFacade(subDir.resolve("wal.log"), subDir.resolve("snapshots"), storage, storage);
        storage.attachRecoveryManager(wal);
        ccm = new ConcurrencyControlManager(protocol, wal);
        ccm.initAfterRecovery();

        QueryOptimizer optimizer = new QueryOptimizer(storage, new ShaylaParser()) {
            @Override
            public ParsedQuery optimizeQuery(ParsedQuery query) {
                return query;
            }
        };
        processor = new QueryProcessor(optimizer, storage, storage, wal, ccm);

        new CreateTableOperator(storage).execute(new TableSchema("accounts", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("balance", DataType.INTEGER, 0, false)), List.of("id"), List.of()));
        new CreateTableOperator(storage).execute(new TableSchema("audit", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("note", DataType.VARCHAR, 50, false)), List.of("id"), List.of()));

        int seed = ccm.beginTransaction();
        assertOk(processor.executeQuery("INSERT INTO accounts VALUES (1, 100);", seed));
        assertOk(processor.executeQuery("INSERT INTO accounts VALUES (2, 200);", seed));
        assertOk(processor.executeQuery("INSERT INTO accounts VALUES (3, 300);", seed));
        assertOk(processor.executeQuery("INSERT INTO audit VALUES (1, 'init');", seed));
        ccm.commitTransaction(seed);
    }

    // 1. Same-row read/write and write/write prevent dirty reads and lost updates
    @Test
    void sameRowReadWriteAndWriteWritePreventDirtyReadAndLostUpdate() throws Exception {
        // Write -> Read (Reader must wait for writer and never see uncommitted dirty value if rolled back)
        int writer = ccm.beginTransaction();
        int reader = ccm.beginTransaction();
        assertOk(processor.executeQuery("UPDATE accounts SET balance = 999 WHERE id = 1;", writer));

        Future<ExecutionResult> readFuture = executor.submit(
                () -> processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", reader));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));

        ccm.abortTransaction(writer, "rollback dirty write");
        ExecutionResult readResult = readFuture.get(5, TimeUnit.SECONDS);
        assertOk(readResult);
        assertEquals(List.of(List.of(100)), readResult.rows());
        ccm.commitTransaction(reader);

        // Write -> Write with relative expression (balance = balance + 50) prevents lost update
        int tx1 = ccm.beginTransaction();
        int tx2 = ccm.beginTransaction();
        assertOk(processor.executeQuery("UPDATE accounts SET balance = balance + 50 WHERE id = 1;", tx1));

        Future<ExecutionResult> update2 = executor.submit(
                () -> processor.executeQuery("UPDATE accounts SET balance = balance + 30 WHERE id = 1;", tx2));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));

        ccm.commitTransaction(tx1);
        assertOk(update2.get(5, TimeUnit.SECONDS));
        ccm.commitTransaction(tx2);

        int verifyTx = ccm.beginTransaction();
        ExecutionResult finalBalance = processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", verifyTx);
        assertEquals(List.of(List.of(180)), finalBalance.rows());
        ccm.commitTransaction(verifyTx);
        assertCleanResources();
    }

    // 2. Reader-to-writer upgrade after intervening commit cannot overwrite newer value from stale state
    @Test
    void readerToWriterUpgradeAfterInterveningCommitRejectsStaleOverwrite() throws Exception {
        int tx1 = ccm.beginTransaction();
        int tx2 = ccm.beginTransaction();

        // Both transactions read id=1 (balance=100), acquiring shared locks
        assertEquals(List.of(List.of(100)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", tx1).rows());
        assertEquals(List.of(List.of(100)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", tx2).rows());

        // tx1 attempts to upgrade to exclusive lock; blocks while tx2 holds S lock
        Future<ExecutionResult> tx1Update = executor.submit(
                () -> processor.executeQuery("UPDATE accounts SET balance = 150 WHERE id = 1;", tx1));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));

        // tx2 also tries to upgrade -> deadlock victim (tx2 is younger) or if tx1 commits first, stale check rejects
        ExecutionResult tx2Update = processor.executeQuery("UPDATE accounts SET balance = 250 WHERE id = 1;", tx2);
        assertFalse(tx2Update.success());
        assertNull(ccm.getTransaction(tx2));

        assertOk(tx1Update.get(5, TimeUnit.SECONDS));
        ccm.commitTransaction(tx1);

        // the victim retries and sees the committed value, nothing was overwritten from stale state
        int retry = ccm.beginTransaction();
        assertOk(processor.executeQuery("UPDATE accounts SET balance = balance + 25 WHERE id = 1;", retry));
        ccm.commitTransaction(retry);

        int probe = ccm.beginTransaction();
        assertEquals(List.of(List.of(175)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", probe).rows());
        ccm.commitTransaction(probe);
        assertCleanResources();
    }

    // under timestamp ordering an older reader can never upgrade over a newer committed write
    @Test
    void timestampOrderingRejectsStaleUpgradeAfterInterveningCommit() {
        initEngine(new TimestampsBasedProtocol());
        int older = ccm.beginTransaction();
        int younger = ccm.beginTransaction();
        assertEquals(List.of(List.of(100)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", older).rows());

        assertOk(processor.executeQuery("UPDATE accounts SET balance = 175 WHERE id = 1;", younger));
        ccm.commitTransaction(younger);

        assertFalse(processor.executeQuery("UPDATE accounts SET balance = 999 WHERE id = 1;", older).success());
        assertNull(ccm.getTransaction(older));

        int probe = ccm.beginTransaction();
        assertEquals(List.of(List.of(175)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", probe).rows());
        ccm.commitTransaction(probe);
        assertCleanResources();
    }

    // INSERTs into the same table do not serialize each other, but a scan blocks them (phantom policy)
    @Test
    void concurrentInsertsIntoSameTableProceedWithoutBlockingEachOther() throws Exception {
        int a = ccm.beginTransaction();
        int b = ccm.beginTransaction();
        assertOk(processor.executeQuery("INSERT INTO accounts VALUES (10, 1);", a));
        Future<ExecutionResult> other = executor.submit(
                () -> processor.executeQuery("INSERT INTO accounts VALUES (11, 2);", b));
        assertOk(other.get(3, TimeUnit.SECONDS));
        assertEquals(0, ccm.getWaitingRequestCount());
        ccm.commitTransaction(a);
        ccm.commitTransaction(b);
        assertCleanResources();
    }

    // 3. Independent rows/tables progress without unnecessary global serialization
    @Test
    void independentRowsAndTablesProgressConcurrentlyWhileRowLockIsHeld() throws Exception {
        int holder = ccm.beginTransaction();
        assertOk(processor.executeQuery("UPDATE accounts SET balance = 111 WHERE id = 1;", holder));

        CyclicBarrier barrier = new CyclicBarrier(2);
        int otherRowTx = ccm.beginTransaction();
        int otherTableTx = ccm.beginTransaction();

        Future<ExecutionResult> rowFuture = executor.submit(() -> {
            barrier.await(3, TimeUnit.SECONDS);
            return processor.executeQuery("UPDATE accounts SET balance = 222 WHERE id = 2;", otherRowTx);
        });
        Future<ExecutionResult> tableFuture = executor.submit(() -> {
            barrier.await(3, TimeUnit.SECONDS);
            return processor.executeQuery("UPDATE audit SET note = 'updated' WHERE id = 1;", otherTableTx);
        });

        assertOk(rowFuture.get(5, TimeUnit.SECONDS));
        assertOk(tableFuture.get(5, TimeUnit.SECONDS));
        ccm.commitTransaction(otherRowTx);
        ccm.commitTransaction(otherTableTx);
        ccm.commitTransaction(holder);

        int check = ccm.beginTransaction();
        assertEquals(List.of(List.of(111)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", check).rows());
        assertEquals(List.of(List.of(222)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 2;", check).rows());
        assertEquals(List.of(List.of("updated")),
                processor.executeQuery("SELECT note FROM audit WHERE id = 1;", check).rows());
        ccm.commitTransaction(check);
        assertCleanResources();
    }

    // 4. Opposite lock order and multiple victims resolve with complete undo before resources are released
    @Test
    void oppositeLockOrderAndMultiCycleDeadlocksUndoVictimsBeforeRelease() throws Exception {
        int tx1 = ccm.beginTransaction();
        Thread.sleep(5);
        int tx2 = ccm.beginTransaction();
        Thread.sleep(5);
        int tx3 = ccm.beginTransaction();

        assertOk(processor.executeQuery("UPDATE accounts SET balance = 1000 WHERE id = 1;", tx1));
        assertOk(processor.executeQuery("UPDATE accounts SET balance = 2000 WHERE id = 2;", tx2));
        assertOk(processor.executeQuery("UPDATE accounts SET balance = 3000 WHERE id = 3;", tx3));

        // tx1 waits on id=2 (held by tx2), tx2 waits on id=3 (held by tx3)
        Future<ExecutionResult> f1 = executor.submit(
                () -> processor.executeQuery("UPDATE accounts SET balance = 1002 WHERE id = 2;", tx1));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));

        Future<ExecutionResult> f2 = executor.submit(
                () -> processor.executeQuery("UPDATE accounts SET balance = 2003 WHERE id = 3;", tx2));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 2, 3_000));

        // tx3 requests id=1 (held by tx1) -> closes 3-tx cycle -> tx3 is victim and completely undone
        ExecutionResult r3 = processor.executeQuery("UPDATE accounts SET balance = 3001 WHERE id = 1;", tx3);
        assertFalse(r3.success());
        assertNull(ccm.getTransaction(tx3));

        // tx2 now acquires id=3; then if tx2 requests id=1 (held by tx1), 2-tx cycle forms -> tx2 is victim and undone
        assertOk(f2.get(5, TimeUnit.SECONDS));
        ExecutionResult r2 = processor.executeQuery("UPDATE accounts SET balance = 2001 WHERE id = 1;", tx2);
        assertFalse(r2.success());
        assertNull(ccm.getTransaction(tx2));

        // tx1 now unblocks on id=2 and commits
        assertOk(f1.get(5, TimeUnit.SECONDS));
        ccm.commitTransaction(tx1);

        // Verify tx2 and tx3 writes were completely undone, and only tx1's updates remain
        int verify = ccm.beginTransaction();
        assertEquals(List.of(List.of(1, 1000), List.of(2, 1002), List.of(3, 300)),
                processor.executeQuery("SELECT id, balance FROM accounts ORDER BY id;", verify).rows());
        ccm.commitTransaction(verify);
        assertCleanResources();
    }

    // 5. Scans concurrent with INSERT/DELETE match explicit phantom policy (table/range protection)
    @Test
    void scanProtectsAgainstConcurrentInsertAndDeletePhantoms() throws Exception {
        int scanner = ccm.beginTransaction();
        int inserter = ccm.beginTransaction();

        List<List<Object>> firstScan = processor.executeQuery(
                "SELECT id, balance FROM accounts ORDER BY id;", scanner).rows();
        assertEquals(3, firstScan.size());

        // Concurrent INSERT on accounts blocks on the table scan range lock held by scanner
        Future<ExecutionResult> insertFuture = executor.submit(
                () -> processor.executeQuery("INSERT INTO accounts VALUES (4, 400);", inserter));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));

        // Repeating the scan in scanner returns the exact same set of rows (no phantom row 4)
        List<List<Object>> secondScan = processor.executeQuery(
                "SELECT id, balance FROM accounts ORDER BY id;", scanner).rows();
        assertEquals(firstScan, secondScan);

        ccm.commitTransaction(scanner);
        assertOk(insertFuture.get(5, TimeUnit.SECONDS));
        ccm.commitTransaction(inserter);

        // Uncommitted INSERT also blocks a new scanner until the inserter finishes
        int uncommittedInserter = ccm.beginTransaction();
        int contenderScanner = ccm.beginTransaction();
        assertOk(processor.executeQuery("INSERT INTO accounts VALUES (5, 500);", uncommittedInserter));

        Future<ExecutionResult> scanFuture = executor.submit(
                () -> processor.executeQuery("SELECT id FROM accounts ORDER BY id;", contenderScanner));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));

        ccm.abortTransaction(uncommittedInserter, "abort insert 5");
        ExecutionResult afterAbortScan = scanFuture.get(5, TimeUnit.SECONDS);
        assertOk(afterAbortScan);
        assertEquals(List.of(List.of(1), List.of(2), List.of(3), List.of(4)), afterAbortScan.rows());
        ccm.commitTransaction(contenderScanner);

        // Uncommitted DELETE also blocks a concurrent scanner so it never misses a row that gets rolled back
        int uncommittedDeleter = ccm.beginTransaction();
        int deleteContenderScanner = ccm.beginTransaction();
        assertOk(processor.executeQuery("DELETE FROM accounts WHERE id = 4;", uncommittedDeleter));

        Future<ExecutionResult> deleteScanFuture = executor.submit(
                () -> processor.executeQuery("SELECT id FROM accounts ORDER BY id;", deleteContenderScanner));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));

        ccm.abortTransaction(uncommittedDeleter, "abort delete 4");
        ExecutionResult afterAbortDeleteScan = deleteScanFuture.get(5, TimeUnit.SECONDS);
        assertOk(afterAbortDeleteScan);
        assertEquals(List.of(List.of(1), List.of(2), List.of(3), List.of(4)), afterAbortDeleteScan.rows());
        ccm.commitTransaction(deleteContenderScanner);
        assertCleanResources();
    }

    // 6. Cancellation, timeouts, and disconnect signals bound waits and leave no leaked transactions/resources
    @Test
    void cancellationAndTimeoutsBoundWaitsAndCleanUpAllResources() throws Exception {
        int holder = ccm.beginTransaction();
        assertOk(processor.executeQuery("UPDATE accounts SET balance = 777 WHERE id = 1;", holder));

        // 6a. client disconnect / shutdown: ClientHandler.disconnect() aborts the tx from another thread
        int disconnectedTx = ccm.beginTransaction();
        Future<ExecutionResult> disconnectedFuture = executor.submit(
                () -> processor.executeQuery("UPDATE accounts SET balance = 888 WHERE id = 1;", disconnectedTx));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));
        ccm.abortTransaction(disconnectedTx, "Client disconnected");
        assertFalse(disconnectedFuture.get(5, TimeUnit.SECONDS).success());
        assertNull(ccm.getTransaction(disconnectedTx));
        assertEquals(0, ccm.getWaitingRequestCount());

        // 6a'. thread interrupt (what workerPool.shutdownNow() does on server shutdown)
        int interruptedTx = ccm.beginTransaction();
        Future<ExecutionResult> interruptedFuture = executor.submit(
                () -> processor.executeQuery("UPDATE accounts SET balance = 889 WHERE id = 1;", interruptedTx));
        assertTrue(awaitCondition(() -> ccm.getWaitingRequestCount() == 1, 3_000));
        interruptedFuture.cancel(true);
        assertTrue(awaitCondition(() -> ccm.getTransaction(interruptedTx) == null, 3_000));
        assertEquals(0, ccm.getWaitingRequestCount());

        // 6b. Bounded wait timeout while waiting
        AccessPermission.setWaitTimeoutMillis(150);
        int timedOutTx = ccm.beginTransaction();
        ExecutionResult timedOutResult = processor.executeQuery(
                "UPDATE accounts SET balance = 999 WHERE id = 1;", timedOutTx);
        assertFalse(timedOutResult.success());
        assertNull(ccm.getTransaction(timedOutTx));
        assertEquals(0, ccm.getWaitingRequestCount());
        AccessPermission.resetWaitTimeout();

        ccm.abortTransaction(holder, "release holder");
        int verify = ccm.beginTransaction();
        assertEquals(List.of(List.of(100)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", verify).rows());
        ccm.commitTransaction(verify);
        assertCleanResources();
    }

    // 7. Timestamp-based concurrency runs relevant cases with documented outcomes (abort older on conflict, restart with larger TS)
    @Test
    void timestampBasedConcurrencyAbortsOutOfOrderConflictAndSucceedsOnRestart() {
        initEngine(new TimestampsBasedProtocol());

        int olderTx = ccm.beginTransaction();
        int youngerTx = ccm.beginTransaction();
        Transaction olderSnap = ccm.getTransaction(olderTx);
        Transaction youngerSnap = ccm.getTransaction(youngerTx);
        assertTrue(youngerSnap.getTimestamp() > olderSnap.getTimestamp());

        // Younger transaction writes id=1 first
        assertOk(processor.executeQuery("UPDATE accounts SET balance = 555 WHERE id = 1;", youngerTx));

        // Older transaction attempting to read or write id=1 is immediately aborted (non-blocking) and rolled back
        ExecutionResult olderResult = processor.executeQuery(
                "UPDATE accounts SET balance = 444 WHERE id = 1;", olderTx);
        assertFalse(olderResult.success());
        assertNull(ccm.getTransaction(olderTx));

        ccm.commitTransaction(youngerTx);

        // Restarted transaction gets a larger timestamp and succeeds
        int restartedTx = ccm.beginTransaction();
        assertNotEquals(olderTx, restartedTx);
        assertTrue(ccm.getTransaction(restartedTx).getTimestamp() > youngerSnap.getTimestamp());
        assertOk(processor.executeQuery("UPDATE accounts SET balance = balance + 45 WHERE id = 1;", restartedTx));
        ccm.commitTransaction(restartedTx);

        int verify = ccm.beginTransaction();
        assertEquals(List.of(List.of(600)),
                processor.executeQuery("SELECT balance FROM accounts WHERE id = 1;", verify).rows());
        ccm.commitTransaction(verify);
        assertCleanResources();
    }

    private static void assertOk(ExecutionResult result) {
        assertTrue(result.success(), result.message());
    }

    private void assertCleanResources() {
        assertEquals(0, ccm.getActiveTransactionCount(), "No active transactions should remain");
        assertEquals(0, ccm.getLockCount(), "No locks should remain");
        assertEquals(0, ccm.getWaitingRequestCount(), "No waiting lock requests should remain");
    }

    private static boolean awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }
}
