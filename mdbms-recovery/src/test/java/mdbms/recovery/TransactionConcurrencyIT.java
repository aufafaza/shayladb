package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.AccessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic concurrency integration tests untuk Issue #61 (Tahap 1: FRM + CCM + SM).
 * Memakai CountDownLatch/CyclicBarrier dan bounded waits terhadap LockBasedProtocol
 * serta TimestampsBasedProtocol dengan StorageManager dan FailureRecoveryFacade asli.
 */
class TransactionConcurrencyIT {

    private static final String TABLE_A = "akun_a";
    private static final String TABLE_B = "akun_b";
    private static final int BUFFER_CAPACITY = 16;
    private static final int INDEX_MAX_DEGREE = 4;
    private static final long TIMEOUT_SECONDS = 5L;

    private static ClassLoader siblingClassLoader;

    @TempDir
    Path tempDir;

    // AC #1: Same-row read/write dan write/write mencegah dirty read & lost update (Lock-Based)
    @Test
    void lockBasedSameRowReadWriteAndWriteWritePreventDirtyReadsAndLostUpdates() throws Exception {
        Path dir = tempDir.resolve("c1_lock_same_row");
        Object sm = createInitializedStorage(dir, TABLE_A);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createCcm(frm, true);
        invokeCcmInitAfterRecovery(ccm);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RowId rid = seedRow(ccm, da, TABLE_A, List.of(1, "initial", 100));
            DataItem item = new DataItem(TABLE_A, rid);

            // Bagian 1a: Pencegahan Dirty Read (Tx1 menulis lalu ABORT, Tx2 terblokir sampai Tx1 selesai)
            CountDownLatch tx1Wrote = new CountDownLatch(1);
            CountDownLatch tx2Blocked = new CountDownLatch(1);

            Future<?> f1 = pool.submit(() -> {
                int tx1 = ccm.beginTransaction();
                List<Object> before = da.fetchByRowId(rid).values();
                List<Object> dirtyVal = List.of(1, "uncommitted", 999);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, item, before, dirtyVal));
                da.updateByRowId(rid, dirtyVal);
                tx1Wrote.countDown();
                assertTrue(tx2Blocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                // Abort Tx1 -> rollback FRM harus memulihkan nilai sebelum lock dilepas
                ccm.abortTransaction(tx1, "Simulasi abort untuk uji dirty read");
                return null;
            });

            Future<List<Object>> f2 = pool.submit(() -> {
                assertTrue(tx1Wrote.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                int tx2 = ccm.beginTransaction();
                // Saat Tx1 masih memegang X-lock, Tx2 wajib mendapat WAITING (tidak boleh membaca dirtyVal)
                assertEquals(AccessStatus.WAITING, ccm.requestRead(tx2, item));
                tx2Blocked.countDown();

                AccessStatus granted = awaitAccess(ccm, () -> ccm.requestRead(tx2, item), TIMEOUT_SECONDS);
                assertEquals(AccessStatus.GRANTED, granted);
                List<Object> observed = da.fetchByRowId(rid).values();
                ccm.commitTransaction(tx2);
                return observed;
            });

            f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Object> observedByTx2 = f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(List.of(1, "initial", 100), observedByTx2,
                    "Tx2 tidak boleh melihat data uncommitted milik Tx1 yang di-abort");

            // Bagian 1b: Pencegahan Lost Update (Tx3 & Tx4 sama-sama menambah saldo +50 dan +30)
            CountDownLatch tx3Locked = new CountDownLatch(1);
            CountDownLatch tx4Waiting = new CountDownLatch(1);

            Future<?> f3 = pool.submit(() -> {
                int tx3 = ccm.beginTransaction();
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx3, item));
                List<Object> cur = da.fetchByRowId(rid).values();
                List<Object> next = List.of(1, "tx3", ((Integer) cur.get(2)) + 50);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx3, item, cur, next));
                da.updateByRowId(rid, next);
                tx3Locked.countDown();
                assertTrue(tx4Waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                ccm.commitTransaction(tx3);
                return null;
            });

            Future<?> f4 = pool.submit(() -> {
                assertTrue(tx3Locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                int tx4 = ccm.beginTransaction();
                assertEquals(AccessStatus.WAITING, ccm.requestWrite(tx4, item));
                tx4Waiting.countDown();

                AccessStatus granted = awaitAccess(ccm, () -> ccm.requestWrite(tx4, item), TIMEOUT_SECONDS);
                assertEquals(AccessStatus.GRANTED, granted);
                List<Object> cur = da.fetchByRowId(rid).values();
                List<Object> next = List.of(1, "tx4", ((Integer) cur.get(2)) + 30);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx4, item, cur, next));
                da.updateByRowId(rid, next);
                ccm.commitTransaction(tx4);
                return null;
            });

            f3.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            f4.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(180, da.fetchByRowId(rid).values().get(2),
                    "Kedua pembaruan (+50 dan +30) harus terakumulasi tanpa lost update");
            assertNoLeakedCcmState(ccm);
        } finally {
            pool.shutdownNow();
            frm.close();
        }
    }

    // AC #2: Reader-to-writer upgrade tidak boleh menimpa nilai baru dari stale state (Lock-Based)
    @Test
    void lockBasedReaderToWriterUpgradePreventsStaleOverwrite() throws Exception {
        Path dir = tempDir.resolve("c2_upgrade_stale");
        Object sm = createInitializedStorage(dir, TABLE_A);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createCcm(frm, true);
        invokeCcmInitAfterRecovery(ccm);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RowId rid = seedRow(ccm, da, TABLE_A, List.of(1, "v1", 100));
            DataItem item = new DataItem(TABLE_A, rid);

            CyclicBarrier bothRead = new CyclicBarrier(2);
            CountDownLatch tx1RequestedUpgrade = new CountDownLatch(1);
            AtomicReference<AccessStatus> tx1UpgradeStatus = new AtomicReference<>();
            AtomicReference<AccessStatus> tx2UpgradeStatus = new AtomicReference<>();

            Future<?> f1 = pool.submit(() -> {
                int tx1 = ccm.beginTransaction();
                assertEquals(AccessStatus.GRANTED, ccm.requestRead(tx1, item));
                List<Object> readVal = da.fetchByRowId(rid).values();
                bothRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Tx1 mencoba upgrade S -> X (menunggu karena Tx2 juga pegang S-lock)
                AccessStatus st = ccm.requestWrite(tx1, item);
                tx1UpgradeStatus.set(st);
                tx1RequestedUpgrade.countDown();
                if (st == AccessStatus.WAITING) {
                    st = awaitAccess(ccm, () -> ccm.requestWrite(tx1, item), TIMEOUT_SECONDS);
                    tx1UpgradeStatus.set(st);
                }
                if (st == AccessStatus.GRANTED) {
                    List<Object> next = List.of(1, "by-tx1", ((Integer) readVal.get(2)) + 10);
                    assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, item, readVal, next));
                    da.updateByRowId(rid, next);
                    ccm.commitTransaction(tx1);
                }
                return null;
            });

            Future<?> f2 = pool.submit(() -> {
                int tx2 = ccm.beginTransaction();
                assertEquals(AccessStatus.GRANTED, ccm.requestRead(tx2, item));
                List<Object> readVal = da.fetchByRowId(rid).values();
                bothRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                assertTrue(tx1RequestedUpgrade.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                // Tx2 ikut mencoba upgrade S -> X -> memicu deadlock upgrade, salah satu harus ABORT
                AccessStatus st = ccm.requestWrite(tx2, item);
                tx2UpgradeStatus.set(st);
                if (st == AccessStatus.WAITING) {
                    st = awaitAccess(ccm, () -> ccm.requestWrite(tx2, item), TIMEOUT_SECONDS);
                    tx2UpgradeStatus.set(st);
                }
                if (st == AccessStatus.GRANTED) {
                    List<Object> next = List.of(1, "by-tx2", ((Integer) readVal.get(2)) + 20);
                    assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx2, item, readVal, next));
                    da.updateByRowId(rid, next);
                    ccm.commitTransaction(tx2);
                }
                return null;
            });

            f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            boolean oneAborted = tx1UpgradeStatus.get() == AccessStatus.ABORTED
                    || tx2UpgradeStatus.get() == AccessStatus.ABORTED;
            boolean oneGranted = tx1UpgradeStatus.get() == AccessStatus.GRANTED
                    || tx2UpgradeStatus.get() == AccessStatus.GRANTED;
            assertTrue(oneAborted && oneGranted,
                    "Upgrade konkuren S->X harus meng-abort satu transaksi dan meloloskan satu pemenang");

            List<Object> finalRow = da.fetchByRowId(rid).values();
            if (tx1UpgradeStatus.get() == AccessStatus.GRANTED) {
                assertEquals(List.of(1, "by-tx1", 110), finalRow);
            } else {
                assertEquals(List.of(1, "by-tx2", 120), finalRow);
            }
            assertNoLeakedCcmState(ccm);
        } finally {
            pool.shutdownNow();
            frm.close();
        }
    }

    // AC #3: Baris dan tabel independen dapat maju paralel tanpa global serialization
    @Test
    void lockBasedIndependentRowsAndTablesProgressWithoutGlobalSerialization() throws Exception {
        Path dir = tempDir.resolve("c3_independent_progress");
        Object sm = createInitializedStorage(dir, TABLE_A, TABLE_B);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createCcm(frm, true);
        invokeCcmInitAfterRecovery(ccm);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RowId ridA1 = seedRow(ccm, da, TABLE_A, List.of(1, "a1", 10));
            RowId ridA2 = seedRow(ccm, da, TABLE_A, List.of(2, "a2", 20));
            RowId ridB1 = seedRow(ccm, da, TABLE_B, List.of(1, "b1", 30));

            CountDownLatch tx1HoldingLocks = new CountDownLatch(1);
            CountDownLatch tx2CompletedIndependently = new CountDownLatch(1);

            // Tx1 menahan X-lock pada ridA1 sampai Tx2 selesai penuh pada ridA2 & ridB1
            Future<?> f1 = pool.submit(() -> {
                int tx1 = ccm.beginTransaction();
                List<Object> before = da.fetchByRowId(ridA1).values();
                List<Object> after = List.of(1, "a1-mod", 11);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, new DataItem(TABLE_A, ridA1), before, after));
                da.updateByRowId(ridA1, after);
                tx1HoldingLocks.countDown();

                // Jika ada global lock, tx2CompletedIndependently akan timeout!
                assertTrue(tx2CompletedIndependently.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Transaksi independen pada baris/tabel lain harus bisa selesai tanpa menunggu Tx1");
                ccm.commitTransaction(tx1);
                return null;
            });

            Future<?> f2 = pool.submit(() -> {
                assertTrue(tx1HoldingLocks.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                int tx2 = ccm.beginTransaction();

                // Ubah baris berbeda pada tabel yang sama (ridA2) dan tabel independen (ridB1)
                List<Object> bA2 = da.fetchByRowId(ridA2).values();
                List<Object> aA2 = List.of(2, "a2-mod", 22);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx2, new DataItem(TABLE_A, ridA2), bA2, aA2));
                da.updateByRowId(ridA2, aA2);

                List<Object> bB1 = da.fetchByRowId(ridB1).values();
                List<Object> aB1 = List.of(1, "b1-mod", 33);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx2, new DataItem(TABLE_B, ridB1), bB1, aB1));
                da.updateByRowId(ridB1, aB1);

                ccm.commitTransaction(tx2);
                tx2CompletedIndependently.countDown();
                return null;
            });

            f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(List.of(1, "a1-mod", 11), da.fetchByRowId(ridA1).values());
            assertEquals(List.of(2, "a2-mod", 22), da.fetchByRowId(ridA2).values());
            assertEquals(List.of(1, "b1-mod", 33), da.fetchByRowId(ridB1).values());
            assertNoLeakedCcmState(ccm);
        } finally {
            pool.shutdownNow();
            frm.close();
        }
    }

    // AC #4: Opposite lock order deadlock menyelesaikan undo korban SEBELUM melepas lock ke pemenang
    @Test
    void lockBasedOppositeLockOrderDeadlockCompletesUndoBeforeLockRelease() throws Exception {
        Path dir = tempDir.resolve("c4_deadlock_undo_before_release");
        Object sm = createInitializedStorage(dir, TABLE_A);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createCcm(frm, true);
        invokeCcmInitAfterRecovery(ccm);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RowId rid1 = seedRow(ccm, da, TABLE_A, List.of(1, "orig1", 100));
            RowId rid2 = seedRow(ccm, da, TABLE_A, List.of(2, "orig2", 200));
            DataItem item1 = new DataItem(TABLE_A, rid1);
            DataItem item2 = new DataItem(TABLE_A, rid2);

            CyclicBarrier firstLocksAcquired = new CyclicBarrier(2);
            CountDownLatch tx1WaitingOnItem2 = new CountDownLatch(1);
            AtomicReference<String> survivor = new AtomicReference<>();
            AtomicReference<List<Object>> victimRowObservedBySurvivor = new AtomicReference<>();

            // Tx1: kunci & ubah item1 -> minta item2
            Future<?> f1 = pool.submit(() -> {
                int tx1 = ccm.beginTransaction();
                List<Object> before1 = da.fetchByRowId(rid1).values();
                List<Object> dirty1 = List.of(1, "tx1-dirty", 111);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, item1, before1, dirty1));
                da.updateByRowId(rid1, dirty1);

                firstLocksAcquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                AccessStatus st = ccm.requestWrite(tx1, item2);
                tx1WaitingOnItem2.countDown();
                if (st == AccessStatus.WAITING) {
                    st = awaitAccess(ccm, () -> ccm.requestWrite(tx1, item2), TIMEOUT_SECONDS);
                }
                if (st == AccessStatus.GRANTED) {
                    // Begitu lock item2 didapat, undo korban (Tx2) HARUS sudah tuntas 100%!
                    victimRowObservedBySurvivor.set(da.fetchByRowId(rid2).values());
                    survivor.set("TX1");
                    ccm.commitTransaction(tx1);
                }
                return null;
            });

            // Tx2: kunci & ubah item2 -> tunggu Tx1 masuk antrean -> minta item1 (memicu siklus deadlock)
            Future<?> f2 = pool.submit(() -> {
                int tx2 = ccm.beginTransaction();
                List<Object> before2 = da.fetchByRowId(rid2).values();
                List<Object> dirty2 = List.of(2, "tx2-dirty", 222);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx2, item2, before2, dirty2));
                da.updateByRowId(rid2, dirty2);

                firstLocksAcquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertTrue(tx1WaitingOnItem2.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

                AccessStatus st = ccm.requestWrite(tx2, item1);
                if (st == AccessStatus.WAITING) {
                    st = awaitAccess(ccm, () -> ccm.requestWrite(tx2, item1), TIMEOUT_SECONDS);
                }
                if (st == AccessStatus.GRANTED) {
                    // Begitu lock item1 didapat, undo korban (Tx1) HARUS sudah tuntas 100%!
                    victimRowObservedBySurvivor.set(da.fetchByRowId(rid1).values());
                    survivor.set("TX2");
                    ccm.commitTransaction(tx2);
                }
                return null;
            });

            f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertNotNull(survivor.get(), "Harus ada tepat satu transaksi pemenang setelah deadlock");
            if ("TX1".equals(survivor.get())) {
                assertEquals(List.of(2, "orig2", 200), victimRowObservedBySurvivor.get(),
                        "Undo korban Tx2 wajib selesai sebelum lock item2 dilepas ke Tx1");
                assertEquals(List.of(1, "tx1-dirty", 111), da.fetchByRowId(rid1).values());
                assertEquals(List.of(2, "orig2", 200), da.fetchByRowId(rid2).values());
            } else {
                assertEquals(List.of(1, "orig1", 100), victimRowObservedBySurvivor.get(),
                        "Undo korban Tx1 wajib selesai sebelum lock item1 dilepas ke Tx2");
                assertEquals(List.of(1, "orig1", 100), da.fetchByRowId(rid1).values());
                assertEquals(List.of(2, "tx2-dirty", 222), da.fetchByRowId(rid2).values());
            }
            assertNoLeakedCcmState(ccm);
        } finally {
            pool.shutdownNow();
            frm.close();
        }
    }

    // AC #4: Multiple victims di-rollback serentak (serempak via CyclicBarrier) tanpa korupsi data/WAL
    @Test
    void lockBasedMultipleConcurrentRollbackVictimsCompleteUndoCleanly() throws Exception {
        Path dir = tempDir.resolve("c4_multiple_victims");
        Object sm = createInitializedStorage(dir, TABLE_A);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createCcm(frm, true);
        invokeCcmInitAfterRecovery(ccm);

        int victims = 4;
        ExecutorService pool = Executors.newFixedThreadPool(victims);
        try {
            List<RowId> rids = new ArrayList<>();
            for (int i = 1; i <= victims; i++) {
                rids.add(seedRow(ccm, da, TABLE_A, List.of(i, "clean-" + i, i * 10)));
            }

            CyclicBarrier readyToAbort = new CyclicBarrier(victims);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < victims; i++) {
                final int idx = i;
                final RowId rid = rids.get(idx);
                futures.add(pool.submit(() -> {
                    int tx = ccm.beginTransaction();
                    List<Object> before = List.of(idx + 1, "clean-" + (idx + 1), (idx + 1) * 10);
                    List<Object> dirty = List.of(idx + 1, "victim-" + (idx + 1), 9999);
                    assertEquals(AccessStatus.GRANTED,
                            ccm.requestWrite(tx, new DataItem(TABLE_A, rid), before, dirty));
                    da.updateByRowId(rid, dirty);
                    readyToAbort.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    ccm.abortTransaction(tx, "Multi-victim rollback #" + idx);
                    return null;
                }));
            }

            for (Future<?> f : futures) {
                f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            for (int i = 0; i < victims; i++) {
                assertEquals(List.of(i + 1, "clean-" + (i + 1), (i + 1) * 10),
                        da.fetchByRowId(rids.get(i)).values());
            }
            assertNoLeakedCcmState(ccm);
        } finally {
            pool.shutdownNow();
            frm.close();
        }
    }

    // AC #6: Cancellation (Thread.interrupt) & shutdown membatasi wait, menuntaskan undo, tanpa leak resource/WAL rusak
    @Test
    void lockBasedCancellationAndShutdownBoundWaitsAndLeaveNoLeakedResources() throws Exception {
        Path dir = tempDir.resolve("c6_cancel_shutdown");
        Object sm = createInitializedStorage(dir, TABLE_A);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createCcm(frm, true);
        invokeCcmInitAfterRecovery(ccm);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RowId rid1 = seedRow(ccm, da, TABLE_A, List.of(1, "base1", 100));
            RowId rid2 = seedRow(ccm, da, TABLE_A, List.of(2, "base2", 200));
            DataItem item1 = new DataItem(TABLE_A, rid1);
            DataItem item2 = new DataItem(TABLE_A, rid2);

            // 6a. Tx1 menahan lock item1, Tx2 menunggu pada executeRelocation lalu di-cancel via Thread.interrupt()
            int tx1 = ccm.beginTransaction();
            assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, item1));

            CountDownLatch tx2EnteredWait = new CountDownLatch(1);
            AtomicReference<Thread> tx2Thread = new AtomicReference<>();
            AtomicBoolean cancelledCleanly = new AtomicBoolean(false);

            Future<?> fCancel = pool.submit(() -> {
                tx2Thread.set(Thread.currentThread());
                int tx2 = ccm.beginTransaction();
                // Tulis satu perubahan dulu pada rid2 agar saat cancel terjadi, undo benar-benar diuji pada interrupted thread
                List<Object> b2 = da.fetchByRowId(rid2).values();
                List<Object> d2 = List.of(2, "tx2-before-cancel", 299);
                assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx2, item2, b2, d2));
                da.updateByRowId(rid2, d2);

                tx2EnteredWait.countDown();
                // executeRelocation akan menunggu item1 yang dipegang tx1 hingga ter-interrupt
                assertThrows(IllegalStateException.class, () -> ccm.executeRelocation(
                        tx2, item1, item2, List.of(1, "base1", 100), List.of(1, "reloc", 150),
                        () -> Boolean.TRUE));
                cancelledCleanly.set(true);
                return null;
            });

            assertTrue(tx2EnteredWait.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            // Tunggu sampai Tx2 benar-benar tercatat menunggu di CCM (bounded, tanpa sleep tetap)
            awaitCondition(() -> invokeGetWaitingRequestCount(ccm) > 0, TIMEOUT_SECONDS);

            // Kirim sinyal pembatalan (interrupt) ke Tx2
            tx2Thread.get().interrupt();
            fCancel.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertTrue(cancelledCleanly.get(), "Waiter yang di-cancel harus berhenti dalam waktu terbatas");
            assertEquals(List.of(2, "base2", 200), da.fetchByRowId(rid2).values(),
                    "Undo pada interrupted thread harus tetap menuntaskan pemulihan data");
            assertFalse(invokeIsFailed(ccm),
                    "Pembatalan/interrupt pada satu transaksi tidak boleh merusak WAL atau menjatuhkan engine ke failed state");

            // Tx1 tetap harus bisa commit normal melalui WAL channel bersama
            ccm.commitTransaction(tx1);
            assertNoLeakedCcmState(ccm);

            // 6b. Sinyal shutdown (stopTransactions) menolak transaksi baru secara deterministik
            invokeStopTransactions(ccm);
            assertTrue(ccm.isStopping());
            assertThrows(IllegalStateException.class, ccm::beginTransaction);
        } finally {
            pool.shutdownNow();
            frm.close();
        }
    }

    // Scope Timestamp-Based & AC #2: TimestampsBasedProtocol menolak stale write & FRM meng-undo tuntas
    @Test
    void timestampBasedConcurrencyRejectsStaleWriteAndRollsBackCleanly() {
        Path dir = tempDir.resolve("c_timestamp_protocol");
        Object sm = createInitializedStorage(dir, TABLE_A);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createCcm(frm, false); // TimestampsBasedProtocol
        invokeCcmInitAfterRecovery(ccm);

        try {
            RowId rid1 = seedRow(ccm, da, TABLE_A, List.of(1, "ts-orig1", 100));
            RowId rid2 = seedRow(ccm, da, TABLE_A, List.of(2, "ts-orig2", 200));
            DataItem item1 = new DataItem(TABLE_A, rid1);
            DataItem item2 = new DataItem(TABLE_A, rid2);

            // Tx1 (timestamp lebih tua) mulai duluan dan sudah memodifikasi item2
            int txOld = ccm.beginTransaction();
            List<Object> before2 = da.fetchByRowId(rid2).values();
            List<Object> dirty2 = List.of(2, "ts-old-dirty", 999);
            assertEquals(AccessStatus.GRANTED, ccm.requestWrite(txOld, item2, before2, dirty2));
            da.updateByRowId(rid2, dirty2);

            // Tx2 (timestamp lebih muda) membaca & memperbarui item1 lalu commit
            int txNew = ccm.beginTransaction();
            assertEquals(AccessStatus.GRANTED, ccm.requestRead(txNew, item1));
            List<Object> before1 = da.fetchByRowId(rid1).values();
            List<Object> newer1 = List.of(1, "ts-new-commit", 150);
            assertEquals(AccessStatus.GRANTED, ccm.requestWrite(txNew, item1, before1, newer1));
            da.updateByRowId(rid1, newer1);
            ccm.commitTransaction(txNew);

            // Tx1 (lebih tua) mencoba menulis item1 -> melanggar writeTS/readTS -> WAJIB ABORT + undo item2
            AccessStatus staleStatus = ccm.requestWrite(txOld, item1, before1, List.of(1, "stale-overwrite", 101));
            assertEquals(AccessStatus.ABORTED, staleStatus,
                    "TimestampsBasedProtocol harus menolak write dari transaksi lebih tua terhadap item yang sudah diakses transaksi lebih baru");

            assertEquals(List.of(1, "ts-new-commit", 150), da.fetchByRowId(rid1).values(),
                    "Nilai terbaru milik txNew tidak boleh tertimpa");
            assertEquals(List.of(2, "ts-orig2", 200), da.fetchByRowId(rid2).values(),
                    "Perubahan sebelumnya milik txOld pada item2 harus sudah di-undo tuntas");
            assertNoLeakedCcmState(ccm);
        } finally {
            frm.close();
        }
    }

    // --- Helper Deterministik & Refleksi ---

    private static AccessStatus awaitAccess(
            IConcurrencyControl ccm,
            java.util.function.Supplier<AccessStatus> attempt,
            long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            AccessStatus st = attempt.get();
            if (st != AccessStatus.WAITING) {
                return st;
            }
            if (ccm.isStopping() || Thread.currentThread().isInterrupted()) {
                return AccessStatus.ABORTED;
            }
            LockSupport.parkNanos(2_000_000L);
        }
        throw new AssertionError("Bounded wait habis (" + timeoutSeconds + "s) saat menunggu akses CCM");
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(2_000_000L);
        }
        throw new AssertionError("Kondisi tidak terpenuhi dalam batas waktu " + timeoutSeconds + "s");
    }

    private static RowId seedRow(IConcurrencyControl ccm, IDataAccess da, String table, List<Object> values) {
        int tx = ccm.beginTransaction();
        RowId rid = da.reserveRowId(table, values);
        assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx, new DataItem(table, rid), null, values));
        da.insertAt(rid, values);
        ccm.commitTransaction(tx);
        return rid;
    }

    private static void assertNoLeakedCcmState(IConcurrencyControl ccm) {
        assertFalse(invokeIsFailed(ccm), "Engine CCM tidak boleh masuk failed state");
        assertEquals(0, invokeGetActiveTransactionCount(ccm), "Tidak boleh ada transaksi aktif yang bocor");
        assertEquals(0, invokeGetLockCount(ccm), "Tidak boleh ada lock yang bocor");
        assertEquals(0, invokeGetWaitingRequestCount(ccm), "Tidak boleh ada antrean waiter yang bocor");
    }

    private static Object createInitializedStorage(Path dir, String... tableNames) {
        Object sm = createRealStorageManager(dir);
        ISchemaCatalog catalog = (ISchemaCatalog) sm;
        IDataAccess da = (IDataAccess) sm;
        for (String table : tableNames) {
            if (catalog.getTableSchema(table) == null) {
                TableSchema schema = new TableSchema(
                        table,
                        List.of(
                                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                                new ColumnDefinition("label", DataType.VARCHAR, 100, false),
                                new ColumnDefinition("saldo", DataType.INTEGER, 0, false)
                        ),
                        List.of("id"),
                        List.of()
                );
                catalog.updateTableSchema(schema);
            }
            // Warm-up read agar entri HeapFile masuk ke StorageManager.tables sebelum thread paralel berjalan
            Rows ignored = da.readBlock(new DataRetrieval(table, List.of(), List.of()));
            assertNotNull(ignored);
        }
        return sm;
    }

    private static synchronized ClassLoader getSiblingClassLoader() {
        if (siblingClassLoader != null) {
            return siblingClassLoader;
        }
        try {
            List<URL> urls = new ArrayList<>();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            for (String candidate : new String[] {
                "mdbms-storage/target/classes", "../mdbms-storage/target/classes",
                "mdbms-concurrency/target/classes", "../mdbms-concurrency/target/classes"
            }) {
                Path p = cwd.resolve(candidate).normalize();
                if (Files.exists(p)) {
                    urls.add(p.toUri().toURL());
                }
            }
            siblingClassLoader = new URLClassLoader(urls.toArray(new URL[0]), TransactionConcurrencyIT.class.getClassLoader());
            return siblingClassLoader;
        } catch (Exception e) {
            throw new RuntimeException("Gagal menyiapkan ClassLoader modul sibling", e);
        }
    }

    private static Object createRealStorageManager(Path dataDir) {
        try {
            Class<?> smClass = Class.forName("mdbms.storage.StorageManager", true, getSiblingClassLoader());
            Constructor<?> ctor = smClass.getConstructor(String.class, int.class, int.class);
            return ctor.newInstance(dataDir.toString(), BUFFER_CAPACITY, INDEX_MAX_DEGREE);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat StorageManager", e);
        }
    }

    private static void attachRecoveryManagerToStorage(Object sm, IFailureRecoveryManager frm) {
        try {
            Method m = sm.getClass().getMethod("attachRecoveryManager", IFailureRecoveryManager.class);
            m.invoke(sm, frm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memanggil StorageManager.attachRecoveryManager()", e);
        }
    }

    private static IConcurrencyControl createCcm(IFailureRecoveryManager frm, boolean lockBased) {
        try {
            ClassLoader cl = getSiblingClassLoader();
            Class<?> protocolInterface = Class.forName("mdbms.concurrency.ConcurrencyProtocol", true, cl);
            Class<?> protocolClass = Class.forName(
                    lockBased ? "mdbms.concurrency.LockBasedProtocol" : "mdbms.concurrency.TimestampsBasedProtocol",
                    true, cl);
            Object protocol = protocolClass.getDeclaredConstructor().newInstance();
            Class<?> ccmClass = Class.forName("mdbms.concurrency.ConcurrencyControlManager", true, cl);
            Constructor<?> ctor = ccmClass.getConstructor(protocolInterface, IFailureRecoveryManager.class);
            return (IConcurrencyControl) ctor.newInstance(protocol, frm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat ConcurrencyControlManager", e);
        }
    }

    private static void invokeCcmInitAfterRecovery(IConcurrencyControl ccm) {
        try {
            ccm.getClass().getMethod("initAfterRecovery").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal menjalankan ccm.initAfterRecovery()", e);
        }
    }

    private static void invokeStopTransactions(IConcurrencyControl ccm) {
        try {
            ccm.getClass().getMethod("stopTransactions").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean invokeIsFailed(IConcurrencyControl ccm) {
        try {
            return (boolean) ccm.getClass().getMethod("isFailed").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int invokeGetActiveTransactionCount(IConcurrencyControl ccm) {
        try {
            return (int) ccm.getClass().getMethod("getActiveTransactionCount").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int invokeGetLockCount(IConcurrencyControl ccm) {
        try {
            return (int) ccm.getClass().getMethod("getLockCount").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int invokeGetWaitingRequestCount(IConcurrencyControl ccm) {
        try {
            return (int) ccm.getClass().getMethod("getWaitingRequestCount").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}