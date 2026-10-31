package mdbms.concurrency;

import mdbms.concurrency.control.ConcurrencyControlManager;

import mdbms.concurrency.control.TransactionStatus;
import mdbms.concurrency.control.AccessStatus;
import mdbms.concurrency.control.ScheduleVerifier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CCMTestDriver {
    private ConcurrencyControlManager ccm;
    private mockFRM mockFrm;

    private ExecutorService executor;

    public void setUp() {
        mockFrm = new MockFRM();
        ccm = new ConcurrencyControlManager(mockFrm);
        executor = Executors.newCachedThreadPool();
    }

    public static void main(String[] args) {
        CCMTestDriver driver = new CCMTestDriver();
        driver.setUp();

        driver.testConcurrentBegin();
        driver.testConcurrentReadWrite();
        driver.testLockUpgrade();
        driver.testDeadlockTwoTransactions();
        driver.testTimestampViolation();
        driver.testSerializableSchedule();

        driver.executor.shutdown();
    }

    public void runSchedule(String scheduleScript, String protocol) {
        // set protokol pada ccm
        // parse schedule operasi
        // simulasikan eksekusi operasi sesuai protokol yang dipilih (LOCK atau TIMESTAMP) dengan ada jeda juga untuk menunggu transaksi lain
    }

    public void assertStatus(AccessStatus expectedStatus, AccessStatus actualStatus) {
        
    }

    public void assertCleanState() {
        // Verifikasi LockTable, TransactionTable, dan TimestampTable kosong
    }

    //Skenario di spek

    public void testConcurrentBegin() {
        System.out.println("Skenario: Concurrent Begin & Ganti Protokol");
        // TODO: Jalankan T1 BEGIN dan T2 BEGIN bersamaan via executor
        // TODO: Coba panggil ccm.changeProtocol() dan pastikan ditolak
        assertCleanState();
    }

    public void testConcurrentReadWrite() {
        System.out.println("Skenario: Concurrent Read/Write ");
        // TODO: Jalankan T1 R(A) dan T2 W(A) bersamaan via executor
        // assertCleanState();
    }

    public void testLockUpgrade() {
        System.out.println("Skenario: Lock Upgrade");
        // TODO: T1 R(A) -> T1 W(A). Pastikan lock S menjadi X tanpa deadlock
        assertCleanState();
    }

    public void testDeadlockTwoTransactions() {
        System.out.println("Skenario: Deadlock 2 Transaksi");
        // TODO: Verifikasi bahwa FRM dipanggil untuk rollback (CC-04)
        assertCleanState();
    }

    public void testTimestampViolation() {
        System.out.println("Skenario: Pelanggaran Timestamp");
        // TODO: Buat T1 mencoba W(A) setelah T2 R(A) atau W(A)
        // T1 harus ditolak dan dibatalkan, T2 membaca nilai lama jika T1 abort
        assertCleanState();
    }

    public void testSerializableSchedule() {
        System.out.println("Skenario: Cek Serializability Akhir");
        // TODO: Eksekusi jadwal manual yang kompleks
    }
    
}