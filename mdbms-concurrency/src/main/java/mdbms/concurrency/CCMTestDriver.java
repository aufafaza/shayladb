package mdbms.concurrency;

import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.OperationType;
import mdbms.models.transaction.Transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class CCMTestDriver {
    private ConcurrencyControlManager ccm;
    private ExecutorService executor;

    private final List<String> report = new ArrayList<>();
    private String currentScenario = "";

    public void setUp(ConcurrencyProtocol protocol) {
        ccm = new ConcurrencyControlManager(protocol, new MockFRM());
        executor = Executors.newCachedThreadPool();
    }

    public void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    public static void main(String[] args) {
        CCMTestDriver driver = new CCMTestDriver();

        driver.setUp(new LockBasedProtocol());
        System.out.println("Protokol Aktif: " + driver.ccm.getActiveProtocolName());
        driver.testConcurrentBegin();
        driver.testConcurrentReadWrite();
        driver.testLockUpgrade();
        driver.testDeadlockTwoTransactions();
        driver.testDeadlockThreeTransactions();
        driver.testWaitingWithoutCycle();
        driver.tearDown();

        driver.setUp(new TimestampsBasedProtocol());
        System.out.println("Protokol Aktif: " + driver.ccm.getActiveProtocolName());
        driver.testConcurrentBegin();
        driver.testConcurrentReadWriteTimestamp();
        driver.testTimestampViolation();
        driver.testSerializableSchedule();
        driver.tearDown();

        driver.printReport();
    }

    public void require(boolean condition, String message){
        if(condition){
            report.add("[PASS] " + currentScenario + " - " + message);
        }else{
            report.add("[FAIL] " + currentScenario + " - " + message);
        } 
    }

    public void assertStatus(AccessStatus expectedStatus, AccessStatus actualStatus){
        require(expectedStatus == actualStatus, "Expected: " + expectedStatus + ", Actual: " + actualStatus);
    }

    public void assertCleanState() {
        require(ccm.getActiveTransactions().isEmpty(),
                "Tidak ada transaksi aktif tersisa");
    }

    private AccessStatus runInTxThread(java.util.concurrent.Callable<AccessStatus> task) {
        try {
            Future<AccessStatus> future = executor.submit(task);
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            require(false, "Eksekusi thread transaksi gagal: " + e.getMessage());
            return AccessStatus.ABORTED;
        }
    }

    private void printScheduleAndVerify(List<Operation> schedule, boolean expectedSerializable) {
        String schedStr = schedule.stream()
                .map(op -> "T" + op.getTransaction().getId() + ":" + op.getType()
                        + (op.getDataItem() != null ? "(" + op.getDataItem().tableName() + ")" : ""))
                .collect(Collectors.joining(", "));
        ScheduleVerifier verifier = new ScheduleVerifier(schedule);
        boolean isSer = verifier.isSerializable();
        List<Transaction> serialOrder = verifier.getEquivalentSerialSchedule();
        String orderStr = serialOrder == null ? "TIDAK ADA (Siklus)"
                : serialOrder.stream().map(t -> "T" + t.getId()).collect(Collectors.joining(" -> "));

        System.out.println("  Schedule Akhir: [" + schedStr + "]");
        System.out.println("  Serializable: " + isSer + " | Urutan Serial: " + orderStr);
        require(isSer == expectedSerializable,
                "Verifikasi serializability schedule sesuai harapan (" + expectedSerializable + ")");
    }

    private void printReport(){
        System.out.println("\n===HASIL CCM TEST DRIVER===");
        for(String line : report){
            System.out.println(line);
        }
        long passedCount = report.stream().filter(line -> line.startsWith("[PASS]")).count();
        long failedCount = report.stream().filter(line -> line.startsWith("[FAIL]")).count();
        System.out.println("Total Passed: " + passedCount + ", Total Failed: " + failedCount);
    }

    //Skenario di spek

    public void testConcurrentBegin() {
        currentScenario = "Concurrent Begin Transactions";
        System.out.println("Skenario: " + currentScenario);

        CountDownLatch latch = new CountDownLatch(2);

        int[] txs = new int[2];

        executor.submit(() -> {
            int txId = ccm.beginTransaction();
            txs[0] = txId;
            latch.countDown();
        });

        executor.submit(() -> {
            int txId = ccm.beginTransaction();
            txs[1] = txId;
            latch.countDown();
        }); 

        try {
            require(latch.await(5, TimeUnit.SECONDS), "Kedua tes transaksi selesai dalam waktu 5 detik");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            require(false, "Tes transaksi terganggu saat BEGIN");
            return;
        }

        require(txs[0] != txs[1], "Transaksi yang berbeda memiliki ID yang unik");
        require(ccm.getActiveTransactions().size() == 2, "Ada 2 transaksi aktif setelah BEGIN");

        try {
            ccm.setProtocol(new TimestampsBasedProtocol());
            require(false, "Perubahan protokol seharusnya ditolak karena ada transaksi aktif");
        } catch (IllegalStateException e) {
            require(true, "Perubahan protokol ditolak karena ada transaksi aktif");
        }

        ccm.commitTransaction(txs[0]);
        ccm.commitTransaction(txs[1]);

        assertCleanState();
    }

    public void testConcurrentReadWrite() {
        currentScenario = "Concurrent Read/Write Transactions";
        System.out.println("Skenario: " + currentScenario);

        int tx1 = ccm.beginTransaction();
        int tx2 = ccm.beginTransaction();
        Transaction t1Obj = ccm.getTransaction(tx1);
        Transaction t2Obj = ccm.getTransaction(tx2);
        DataItem itemA = new DataItem("A", null);

        AccessStatus tx1Read = runInTxThread(() -> ccm.requestRead(tx1, itemA));
        AccessStatus tx2Read = runInTxThread(() -> ccm.requestRead(tx2, itemA));
        require(tx1Read == AccessStatus.GRANTED && tx2Read == AccessStatus.GRANTED,
                "Dua read bersamaan diizinkan (Shared Lock)");

        int tx3 = ccm.beginTransaction();
        Transaction t3Obj = ccm.getTransaction(tx3);
        AccessStatus waitingStatus = runInTxThread(() -> ccm.requestWrite(tx3, itemA));
        assertStatus(AccessStatus.WAITING, waitingStatus);
        require(ccm.getTransaction(tx3) != null,
                "T3 tetap aktif ketika lock belum tersedia");

        ccm.commitTransaction(tx1);
        ccm.commitTransaction(tx2);

        AccessStatus retryStatus = runInTxThread(() -> ccm.requestWrite(tx3, itemA));
        assertStatus(AccessStatus.GRANTED, retryStatus);

        ccm.commitTransaction(tx3);
        printScheduleAndVerify(List.of(
                new Operation(t1Obj, OperationType.READ, itemA),
                new Operation(t2Obj, OperationType.READ, itemA),
                new Operation(t3Obj, OperationType.WRITE, itemA)
        ), true);
        assertCleanState();
    }

    public void testLockUpgrade() {
        currentScenario = "Lock Upgrade";
        System.out.println("Skenario: " + currentScenario);
        int tx1 = ccm.beginTransaction();
        Transaction t1Obj = ccm.getTransaction(tx1);
        DataItem itemA = new DataItem("users", null);

        AccessStatus readStatus = runInTxThread(() -> ccm.requestRead(tx1, itemA));
        assertStatus(AccessStatus.GRANTED, readStatus);

        AccessStatus writeStatus = runInTxThread(() -> ccm.requestWrite(tx1, itemA));
        assertStatus(AccessStatus.GRANTED, writeStatus);

        AccessStatus weakerStatus = runInTxThread(() -> ccm.requestRead(tx1, itemA));
        assertStatus(AccessStatus.GRANTED, weakerStatus);

        ccm.commitTransaction(tx1);
        printScheduleAndVerify(List.of(
                new Operation(t1Obj, OperationType.READ, itemA),
                new Operation(t1Obj, OperationType.WRITE, itemA)
        ), true);
        assertCleanState();
    }

    public void testDeadlockTwoTransactions() {
        currentScenario = "Deadlock 2 Transaksi";
        System.out.println("Skenario: " + currentScenario);
        int tx1 = ccm.beginTransaction();
        long tx1Timestamp = ccm.getTransaction(tx1).getTimestamp();
        if (!awaitTimestampAfter(tx1Timestamp)) {
            require(false, "Timestamp T2 harus lebih baru dari T1 untuk uji victim deadlock");
            ccm.abortTransaction(tx1, "test cleanup");
            return;
        }
        int tx2 = ccm.beginTransaction();
        Transaction t1Obj = ccm.getTransaction(tx1);

        DataItem itemA = new DataItem("A", null);
        DataItem itemB = new DataItem("B", null);

        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx1, itemA)));
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx2, itemB)));
        assertStatus(AccessStatus.WAITING, runInTxThread(() -> ccm.requestWrite(tx1, itemB)));
        assertStatus(AccessStatus.ABORTED, runInTxThread(() -> ccm.requestWrite(tx2, itemA)));
        require(ccm.getTransaction(tx2) == null,
                "Transaksi yang dipilih sebagai victim sudah di-abort");

        assertStatus(AccessStatus.ABORTED, ccm.requestRead(tx2, itemA));
        try {
            ccm.commitTransaction(tx2);
            require(false, "Commit setelah aborted harus ditolak");
        } catch (IllegalArgumentException | IllegalStateException e) {
            require(true, "Commit pada transaksi yang sudah aborted ditolak");
        }

        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx1, itemB)));
        ccm.commitTransaction(tx1);
        printScheduleAndVerify(List.of(
                new Operation(t1Obj, OperationType.WRITE, itemA),
                new Operation(t1Obj, OperationType.WRITE, itemB)
        ), true);
        assertCleanState();
    }

    public void testDeadlockThreeTransactions() {
        currentScenario = "Deadlock 3 Transaksi";
        System.out.println("Skenario: " + currentScenario);
        int tx1 = ccm.beginTransaction();
        awaitTimestampAfter(ccm.getTransaction(tx1).getTimestamp());
        int tx2 = ccm.beginTransaction();
        awaitTimestampAfter(ccm.getTransaction(tx2).getTimestamp());
        int tx3 = ccm.beginTransaction();
        Transaction t1Obj = ccm.getTransaction(tx1);
        Transaction t2Obj = ccm.getTransaction(tx2);

        DataItem itemA = new DataItem("A", null);
        DataItem itemB = new DataItem("B", null);
        DataItem itemC = new DataItem("C", null);

        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx1, itemA)));
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx2, itemB)));
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx3, itemC)));

        assertStatus(AccessStatus.WAITING, runInTxThread(() -> ccm.requestWrite(tx1, itemB)));
        assertStatus(AccessStatus.WAITING, runInTxThread(() -> ccm.requestWrite(tx2, itemC)));
        assertStatus(AccessStatus.ABORTED, runInTxThread(() -> ccm.requestWrite(tx3, itemA)));
        require(ccm.getTransaction(tx3) == null, "Victim T3 di-abort pada deadlock 3 transaksi");

        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx2, itemC)));
        ccm.commitTransaction(tx2);
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx1, itemB)));
        ccm.commitTransaction(tx1);

        printScheduleAndVerify(List.of(
                new Operation(t1Obj, OperationType.WRITE, itemA),
                new Operation(t2Obj, OperationType.WRITE, itemB),
                new Operation(t2Obj, OperationType.WRITE, itemC),
                new Operation(t1Obj, OperationType.WRITE, itemB)
        ), true);
        assertCleanState();
    }

    public void testWaitingWithoutCycle() {
        currentScenario = "Menunggu Tanpa Siklus (Tidak Di-abort)";
        System.out.println("Skenario: " + currentScenario);
        int tx1 = ccm.beginTransaction();
        int tx2 = ccm.beginTransaction();
        Transaction t1Obj = ccm.getTransaction(tx1);
        Transaction t2Obj = ccm.getTransaction(tx2);
        DataItem itemA = new DataItem("A", null);

        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx1, itemA)));
        assertStatus(AccessStatus.WAITING, runInTxThread(() -> ccm.requestWrite(tx2, itemA)));
        require(ccm.getTransaction(tx1) != null && ccm.getTransaction(tx2) != null,
                "Transaksi yang menunggu tanpa membentuk siklus tidak dibatalkan");

        ccm.commitTransaction(tx1);
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx2, itemA)));
        ccm.commitTransaction(tx2);

        printScheduleAndVerify(List.of(
                new Operation(t1Obj, OperationType.WRITE, itemA),
                new Operation(t2Obj, OperationType.WRITE, itemA)
        ), true);
        assertCleanState();
    }

    public void testTimestampViolation() {
        currentScenario = "Pelanggaran Timestamp";
        System.out.println("Skenario: " + currentScenario);
        int tx1 = ccm.beginTransaction();
        long tx1Timestamp = ccm.getTransaction(tx1).getTimestamp();
        if (!awaitTimestampAfter(tx1Timestamp)) {
            require(false, "Timestamp T2 harus lebih baru dari T1 untuk uji pelanggaran");
            ccm.abortTransaction(tx1, "test cleanup");
            return;
        }
        int tx2 = ccm.beginTransaction();
        Transaction t2Obj = ccm.getTransaction(tx2);

        DataItem itemA = new DataItem("A", null);

        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx2, itemA)));

        AccessStatus tx1Write = runInTxThread(() -> ccm.requestWrite(tx1, itemA));
        assertStatus(AccessStatus.ABORTED, tx1Write);
        require(ccm.getTransaction(tx1) == null,
                "Transaksi T1 di-abort setelah pelanggaran timestamp");

        awaitTimestampAfter(t2Obj.getTimestamp());
        int tx1Restart = ccm.beginTransaction();
        Transaction t1RestartObj = ccm.getTransaction(tx1Restart);
        require(t1RestartObj.getTimestamp() > t2Obj.getTimestamp(),
                "Transaksi yang dijalankan ulang mendapat timestamp baru yang lebih besar");
        assertStatus(AccessStatus.WAITING, runInTxThread(() -> ccm.requestWrite(tx1Restart, itemA)));
        ccm.commitTransaction(tx2);
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx1Restart, itemA)));
        ccm.commitTransaction(tx1Restart);
        printScheduleAndVerify(List.of(
                new Operation(t2Obj, OperationType.WRITE, itemA),
                new Operation(t1RestartObj, OperationType.WRITE, itemA)
        ), true);
        assertCleanState();
    }

    public void testConcurrentReadWriteTimestamp() {
        currentScenario = "Concurrent Read/Write Transactions (Timestamp)";
        System.out.println("Skenario: " + currentScenario);

        int tx1 = ccm.beginTransaction();
        awaitTimestampAfter(ccm.getTransaction(tx1).getTimestamp());
        int tx2 = ccm.beginTransaction();
        Transaction t1Obj = ccm.getTransaction(tx1);
        Transaction t2Obj = ccm.getTransaction(tx2);
        DataItem itemA = new DataItem("A", null);

        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestRead(tx1, itemA)));
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestRead(tx2, itemA)));
        assertStatus(AccessStatus.GRANTED, runInTxThread(() -> ccm.requestWrite(tx2, itemA)));

        ccm.commitTransaction(tx1);
        ccm.commitTransaction(tx2);

        printScheduleAndVerify(List.of(
                new Operation(t1Obj, OperationType.READ, itemA),
                new Operation(t2Obj, OperationType.READ, itemA),
                new Operation(t2Obj, OperationType.WRITE, itemA)
        ), true);
        assertCleanState();
    }

    public void testSerializableSchedule() {
        currentScenario = "Cek Serializability Akhir";
        System.out.println("Skenario: " + currentScenario);
        int tx1Id = ccm.beginTransaction();
        int tx2Id = ccm.beginTransaction();
        Transaction tx1 = ccm.getTransaction(tx1Id);
        Transaction tx2 = ccm.getTransaction(tx2Id);
        DataItem itemA = new DataItem("A", null);
        DataItem itemB = new DataItem("B", null);

        List<Operation> serialSchedule = List.of(
                new Operation(tx1, OperationType.WRITE, itemA),
                new Operation(tx2, OperationType.READ, itemA),
                new Operation(tx2, OperationType.WRITE, itemB));
        printScheduleAndVerify(serialSchedule, true);

        List<Operation> nonSerializableSchedule = List.of(
                new Operation(tx1, OperationType.WRITE, itemA),
                new Operation(tx2, OperationType.READ, itemA),
                new Operation(tx2, OperationType.WRITE, itemB),
                new Operation(tx1, OperationType.READ, itemB));
        printScheduleAndVerify(nonSerializableSchedule, false);

        ccm.commitTransaction(tx1Id);
        ccm.commitTransaction(tx2Id);
        assertCleanState();
    }

    private boolean awaitTimestampAfter(long timestamp) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.currentTimeMillis() <= timestamp && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return System.currentTimeMillis() > timestamp;
    }

    private static class MockFRM implements IFailureRecoveryManager {
        private RecoveryResult ok() { return new RecoveryResult(true, 1L, 0, "mock"); }
        @Override public RecoveryResult writeTransactionLog(int txId, LogType eventType) { return ok(); }
        @Override public RecoveryResult writeDataLog(int txId, LogType opType, String table, RowId rid, RecordImage beforeImg, RecordImage afterImg) { return ok(); }
        @Override public boolean flushLogUpTo(long lsn) { return true; }
        @Override public RecoveryResult rollbackTransaction(int txId) { return ok(); }
        @Override public RecoveryResult createCheckpoint() { return ok(); }
        @Override public RecoveryResult recoverOnStartup() { return ok(); }
        @Override public RecoveryResult recoverToPointInTime(Instant targetTime) { return ok(); }
        @Override public RecoveryResult recoverToTransaction(int targetTxId) { return ok(); }
        @Override public List<LogRecord> readLogs(Integer filterTxId) { return List.of(); }
    }
}
