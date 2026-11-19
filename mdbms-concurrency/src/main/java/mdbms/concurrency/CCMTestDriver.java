package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.OperationType;
import mdbms.models.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CCMTestDriver {
    private ConcurrencyControlManager ccm;
    private ExecutorService executor;

    private final List<String> report = new ArrayList<>();
    private String currentScenario = "";

    public void setUp(ConcurrencyProtocol protocol) {
        ccm = new ConcurrencyControlManager(protocol);
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
        driver.testConcurrentBegin();
        driver.testConcurrentReadWrite();
        driver.testLockUpgrade();
        driver.testDeadlockTwoTransactions();
        driver.tearDown();

        driver.setUp(new TimestampsBasedProtocol());
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
        DataItem itemA = new DataItem("A", null);

        AccessStatus tx1Read = ccm.requestRead(tx1, itemA);
        require(tx1Read == AccessStatus.GRANTED, "T1 R(A) granted lebih dulu");

        AccessStatus waitingStatus = ccm.requestWrite(tx2, itemA);
        assertStatus(AccessStatus.WAITING, waitingStatus);
        require(ccm.getTransaction(tx2) != null,
                "T2 tetap aktif ketika lock belum tersedia");

        ccm.commitTransaction(tx1);

        AccessStatus retryStatus = ccm.requestWrite(tx2, itemA);
        assertStatus(AccessStatus.GRANTED, retryStatus);

        ccm.abortTransaction(tx2, "test cleanup");
        assertCleanState();
    }

    public void testLockUpgrade() {
        currentScenario = "Lock Upgrade";
        System.out.println("Skenario: " + currentScenario);
        int tx1 = ccm.beginTransaction();
        DataItem itemA = new DataItem("users", null);

        AccessStatus readStatus = ccm.requestRead(tx1, itemA);
        assertStatus(AccessStatus.GRANTED, readStatus);

        AccessStatus writeStatus = ccm.requestWrite(tx1, itemA);
        assertStatus(AccessStatus.GRANTED, writeStatus);
        ccm.commitTransaction(tx1);
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

        DataItem itemA = new DataItem("A", null);
        DataItem itemB = new DataItem("B", null);

        assertStatus(AccessStatus.GRANTED, ccm.requestWrite(tx1, itemA));
        assertStatus(AccessStatus.GRANTED, ccm.requestWrite(tx2, itemB));
        assertStatus(AccessStatus.WAITING, ccm.requestWrite(tx1, itemB));
        assertStatus(AccessStatus.ABORTED, ccm.requestWrite(tx2, itemA));
        require(ccm.getTransaction(tx2) == null,
                "Transaksi yang dipilih sebagai victim sudah di-abort");

        ccm.commitTransaction(tx1);
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

        DataItem itemA = new DataItem("A", null);

        assertStatus(AccessStatus.GRANTED, ccm.requestWrite(tx2, itemA));

        AccessStatus tx1Write = ccm.requestWrite(tx1, itemA);
        assertStatus(AccessStatus.ABORTED, tx1Write);
        require(ccm.getTransaction(tx1) == null,
                "Transaksi T1 di-abort setelah pelanggaran timestamp");

        ccm.commitTransaction(tx2);
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
        require(ccm.verifySchedule(serialSchedule),
                "Jadwal tanpa siklus precedence dinyatakan serializable");

        List<Operation> nonSerializableSchedule = List.of(
                new Operation(tx1, OperationType.WRITE, itemA),
                new Operation(tx2, OperationType.READ, itemA),
                new Operation(tx2, OperationType.WRITE, itemB),
                new Operation(tx1, OperationType.READ, itemB));
        require(!ccm.verifySchedule(nonSerializableSchedule),
                "Jadwal dengan siklus precedence dinyatakan non-serializable");

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
}
