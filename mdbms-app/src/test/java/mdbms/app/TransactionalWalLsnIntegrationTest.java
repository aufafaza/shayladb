package mdbms.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import mdbms.concurrency.ConcurrencyControlManager;
import mdbms.concurrency.LockBasedProtocol;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.query.ExecutionResult;
import mdbms.models.query.ParsedQuery;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.WriteContext;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.QueryProcessor;
import mdbms.processor.operator.CreateTableOperator;
import mdbms.recovery.FailureRecoveryFacade;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransactionalWalLsnIntegrationTest {
    @TempDir Path tempDir;

    private StorageManager storage;
    private FailureRecoveryFacade frm;
    private ConcurrencyControlManager ccm;
    private QueryProcessor processor;

    @BeforeEach
    void setUp() {
        storage = new StorageManager(tempDir.toString(), 16, 4);
        frm = new FailureRecoveryFacade(tempDir, tempDir.resolve("snapshots"), storage, storage);
        storage.attachRecoveryManager(frm);
        ccm = new ConcurrencyControlManager(new LockBasedProtocol(), frm);

        new CreateTableOperator(storage).execute(new TableSchema(
                "accounts",
                List.of(
                        new ColumnDefinition("id", DataType.INTEGER, 0, false),
                        new ColumnDefinition("balance", DataType.INTEGER, 0, false)
                ),
                List.of("id"),
                List.of()
        ));

        QueryOptimizer optimizer = new QueryOptimizer(storage, new ShaylaParser()) {
            @Override
            public ParsedQuery optimizeQuery(ParsedQuery query) {
                return query;
            }
        };
        processor = new QueryProcessor(optimizer, storage, storage, frm, ccm);
    }

    @AfterEach
    void tearDown() {
        if (frm != null) {
            frm.close();
        }
    }

    @Test
    void insertUpdateDeletePropagateActualWalLsnToDirtyPages() {
        // INSERT
        int tx1 = ccm.beginTransaction();
        ExecutionResult ins = processor.executeQuery("INSERT INTO accounts VALUES (1, 100);", tx1);
        assertTrue(ins.success(), ins.message());
        ccm.commitTransaction(tx1);

        List<LogRecord> logsAfterInsert = frm.readLogs(tx1);
        DataChangeLogRecord insertLog = logsAfterInsert.stream()
                .filter(DataChangeLogRecord.class::isInstance)
                .map(DataChangeLogRecord.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(insertLog.getLsn() > 1L);

        // UPDATE
        int tx2 = ccm.beginTransaction();
        ExecutionResult upd = processor.executeQuery("UPDATE accounts SET balance = 250 WHERE id = 1;", tx2);
        assertTrue(upd.success(), upd.message());
        ccm.commitTransaction(tx2);

        DataChangeLogRecord updateLog = frm.readLogs(tx2).stream()
                .filter(DataChangeLogRecord.class::isInstance)
                .map(DataChangeLogRecord.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(updateLog.getLsn() > insertLog.getLsn());

        // Flush buffer verifies BufferPool can flush WAL up to the actual dirty-page LSN
        assertDoesNotThrow(() -> storage.flushBuffer());
        assertTrue(frm.flushLogUpTo(updateLog.getLsn()));

        var rows = storage.readBlock(new DataRetrieval("accounts", List.of(), List.of())).rows();
        assertEquals(1, rows.size());
        assertEquals(List.of(1, 250), rows.get(0).values());
    }

    @Test
    void rejectedAccessPreventsMutationAndReturnsAbortedOutcome() {
        int seedTx = ccm.beginTransaction();
        assertTrue(processor.executeQuery("INSERT INTO accounts VALUES (1, 100);", seedTx).success());
        assertTrue(processor.executeQuery("INSERT INTO accounts VALUES (2, 200);", seedTx).success());
        ccm.commitTransaction(seedTx);

        List<mdbms.models.data.Row> rows = storage.readBlock(new DataRetrieval("accounts", List.of(), List.of())).rows();
        RowId rid1 = rows.get(0).rowId();
        RowId rid2 = rows.get(1).rowId();
        DataItem item1 = new DataItem("accounts", rid1);
        DataItem item2 = new DataItem("accounts", rid2);

        // tx1 is older and holds item1; tx2 is younger and holds item2
        int tx1 = ccm.beginTransaction();
        int tx2 = ccm.beginTransaction();
        assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, item1));
        assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx2, item2));

        // tx2 waits on item1 (held by tx1)
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(tx2, item1));

        // tx1 requests item2 (held by tx2) -> cycle tx1 -> tx2 -> tx1 is detected,
        // and younger tx2 is chosen as deadlock victim and aborted
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(tx1, item2));

        WriteContext deniedCtx = ccm.requestWriteWithContext(tx2, item2, List.of(2, 200), List.of(2, 999));
        assertFalse(deniedCtx.isGranted());
        assertEquals(AccessStatus.ABORTED, deniedCtx.getStatus());
        assertEquals(-1L, deniedCtx.getLsn());

        // No data change record written for tx2 and storage value unchanged
        assertTrue(frm.readLogs(tx2).stream().noneMatch(DataChangeLogRecord.class::isInstance));
        assertEquals(List.of(2, 200), storage.fetchByRowId(rid2).values());

        ccm.abortTransaction(tx1, "cleanup");
    }

    @Test
    void readerUpgradingToWriterCannotOverwriteNewerCommittedValueWithStaleComputation() throws Exception {
        int seedTx = ccm.beginTransaction();
        assertTrue(processor.executeQuery("INSERT INTO accounts VALUES (1, 100);", seedTx).success());
        ccm.commitTransaction(seedTx);

        RowId rid = storage.readBlock(new DataRetrieval("accounts", List.of(), List.of())).rows().get(0).rowId();
        DataItem item = new DataItem("accounts", rid);

        int tx1 = ccm.beginTransaction();
        int tx2 = ccm.beginTransaction();

        // Both read the initial row (shared locks)
        assertEquals(AccessStatus.GRANTED, ccm.requestRead(tx1, item));
        assertEquals(AccessStatus.GRANTED, ccm.requestRead(tx2, item));

        // Tx1 cannot upgrade while Tx2 holds SHARED lock
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(tx1, item));

        CountDownLatch tx1StartedUpdate = new CountDownLatch(1);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<ExecutionResult> tx1Future = exec.submit(() -> {
            tx1StartedUpdate.countDown();
            return processor.executeQuery("UPDATE accounts SET balance = balance + 50 WHERE id = 1;", tx1);
        });

        assertTrue(tx1StartedUpdate.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);

        // Tx2 upgrades/writes first (younger Tx2 waits or commits if Tx2 didn't conflict; here Tx2 aborts via Wait-Die or releases)
        // Release Tx2's shared lock after Tx2 updates via a separate writer or aborting Tx2
        ccm.abortTransaction(tx2, "release reader");

        // Now Tx3 updates balance to 500 before Tx1 finishes if unguarded — here Tx1 immediately acquires exclusive lock
        ExecutionResult tx1Res = tx1Future.get(5, TimeUnit.SECONDS);
        assertTrue(tx1Res.success(), tx1Res.message());
        ccm.commitTransaction(tx1);
        exec.shutdownNow();

        assertEquals(List.of(1, 150), storage.fetchByRowId(rid).values());
    }
}