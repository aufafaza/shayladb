package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test untuk Issue #62:
 * Membuktikan crash recovery menggunakan child JVM terpisah, direktori database terisolasi,
 * crash barrier eksplisit, dan forced termination (Process.destroyForcibly & Runtime.halt)
 * tanpa shutdown hooks.
 */
class CrashRecoveryIT {

    private static final String TABLE = "produk";
    private static final String INDEX_ID = "idx_produk_id";
    private static final int BUFFER_CAPACITY = 16;
    private static final int INDEX_MAX_DEGREE = 4;
    private static final String BARRIER_PREFIX = "BARRIER_REACHED:";
    private static final long CHILD_TIMEOUT_SECONDS = 20L;

    private static ClassLoader siblingClassLoader;

    @TempDir
    Path tempDir;

    // AC #1a: Crash sebelum data logging tidak meninggalkan perubahan uncommitted atau orphan reservation
    @Test
    void crashBeforeDataLoggingLeavesNoUncommittedVisibleChange() throws Exception {
        Path dbDir = tempDir.resolve("ac1a_before_logging");
        runChildAndKillAtBarrier(dbDir, "CRASH_BEFORE_LOGGING");

        Object sm = createRealStorageManager(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
        attachRecoveryManager(sm, frm);
        try {
            assertTrue(frm.recoverOnStartup().isSuccess());
            Map<Integer, List<Object>> rows = readRowsById(da);
            assertEquals(1, rows.size());
            assertEquals(List.of(1, "SEED_ROW"), rows.get(1));
            assertEquals(1, da.searchIndex(INDEX_ID, 1).size());
            assertEquals(0, invokeReleaseOrphanReservations(sm),
                    "Orphan reservation dari transaksi sebelum data logging harus sudah dilepas");
        } finally {
            frm.close();
        }
    }

    // AC #1b: Crash setelah durable data logging tetapi sebelum mutasi storage
    @Test
    void crashAfterDurableDataLoggingBeforeMutationLeavesNoUncommittedVisibleChange() throws Exception {
        Path dbDir = tempDir.resolve("ac1b_after_logging_before_mutation");
        runChildAndKillAtBarrier(dbDir, "CRASH_AFTER_LOGGING_BEFORE_MUTATION");

        Object sm = createRealStorageManager(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
        attachRecoveryManager(sm, frm);
        try {
            RecoveryResult res = frm.recoverOnStartup();
            assertTrue(res.isSuccess(), "Recovery harus sukses: " + res.getMessage());
            Map<Integer, List<Object>> rows = readRowsById(da);
            assertEquals(1, rows.size());
            assertEquals(List.of(1, "SEED_ROW"), rows.get(1));
            assertFalse(rows.containsKey(2));
            assertTrue(da.searchIndex(INDEX_ID, 2).isEmpty());
        } finally {
            frm.close();
        }
    }

    // AC #2: Crash setelah uncommitted page flush (STEAL) menghapus transaksi tidak selesai saat restart
    @Test
    void crashAfterUncommittedPageFlushRemovesIncompleteTransactionOnRestart() throws Exception {
        Path dbDir = tempDir.resolve("ac2_uncommitted_page_flush");
        runChildAndKillAtBarrier(dbDir, "CRASH_AFTER_UNCOMMITTED_FLUSH");

        // Bukti sebelum recovery: file .tbl di disk benar-benar sudah berisi mutasi uncommitted (STEAL)
        String rawTblBefore = Files.readString(dbDir.resolve(TABLE + ".tbl"), StandardCharsets.ISO_8859_1);
        assertTrue(rawTblBefore.contains("UNCOMMITTED_DIRTY_UPDATE") || rawTblBefore.contains("UNCOMMITTED_INSERT"),
                "Halaman dirty uncommitted harus benar-benar sudah ter-flush ke disk sebelum crash");

        Object sm = createRealStorageManager(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
        attachRecoveryManager(sm, frm);
        try {
            assertTrue(frm.recoverOnStartup().isSuccess());
            Map<Integer, List<Object>> rows = readRowsById(da);
            assertEquals(2, rows.size());
            assertEquals(List.of(1, "SEED_ONE"), rows.get(1));
            assertEquals(List.of(2, "SEED_TWO"), rows.get(2));
            assertFalse(rows.containsKey(3));
            assertEquals(1, da.searchIndex(INDEX_ID, 1).size());
            assertEquals(1, da.searchIndex(INDEX_ID, 2).size());
            assertTrue(da.searchIndex(INDEX_ID, 3).isEmpty());
        } finally {
            frm.close();
        }
    }

    // AC #3: Crash setelah durable COMMIT sebelum page flush (NO-FORCE) & sebelum client ack menjaga data committed
    @Test
    void crashAfterDurableCommitBeforePageFlushPreservesCommittedData() throws Exception {
        Path dbDir = tempDir.resolve("ac3_commit_before_page_flush");
        runChildAndKillAtBarrier(dbDir, "CRASH_AFTER_COMMIT_NO_FLUSH");

        // Bukti NO-FORCE: payload committed belum menyentuh berkas .tbl di disk sebelum recovery
        Path tblFile = dbDir.resolve(TABLE + ".tbl");
        if (Files.exists(tblFile)) {
            String rawTblBefore = Files.readString(tblFile, StandardCharsets.ISO_8859_1);
            assertFalse(rawTblBefore.contains("NOFORCE_COMMITTED_PAYLOAD"),
                    "Halaman data tidak boleh sudah ter-flush sebelum crash pada skenario NO-FORCE");
        }

        Object sm = createRealStorageManager(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
        attachRecoveryManager(sm, frm);
        try {
            assertTrue(frm.recoverOnStartup().isSuccess());
            Map<Integer, List<Object>> rows = readRowsById(da);
            assertEquals(2, rows.size());
            assertEquals(List.of(10, "NOFORCE_COMMITTED_PAYLOAD"), rows.get(10));
            assertEquals(List.of(11, "NOFORCE_UPDATED_PAYLOAD"), rows.get(11));
            assertEquals(1, da.searchIndex(INDEX_ID, 10).size());
            assertEquals(1, da.searchIndex(INDEX_ID, 11).size());
            assertEquals(List.of(10, "NOFORCE_COMMITTED_PAYLOAD"),
                    da.fetchByRowId(da.searchIndex(INDEX_ID, 10).get(0)).values());
        } finally {
            frm.close();
        }
    }

    // AC #4: Riwayat INSERT, UPDATE in-place, DELETE, dan relocated-row pulih dengan skema & indeks valid
    @Test
    void insertUpdateDeleteAndRelocatedRowHistoriesRecoverWithUsableSchemasAndIndexes() throws Exception {
        Path dbDir = tempDir.resolve("ac4_full_history_relocation");
        runChildAndKillAtBarrier(dbDir, "CRASH_FULL_HISTORY_NO_FLUSH");

        Object sm = createRealStorageManager(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        ISchemaCatalog catalog = (ISchemaCatalog) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
        attachRecoveryManager(sm, frm);
        try {
            assertTrue(frm.recoverOnStartup().isSuccess());

            TableSchema schema = catalog.getTableSchema(TABLE);
            assertNotNull(schema, "Skema tabel harus pulih dan dapat dipakai");
            assertEquals(1, catalog.getIndexes(TABLE).size(), "Metadata indeks harus pulih");

            Map<Integer, List<Object>> rows = readRowsById(da);
            // id=1 direlokasi ("short" -> "R".repeat(550)), id=2 in-place updated, id=3 deleted, id=4..15 filler committed
            assertEquals(14, rows.size());
            assertEquals(List.of(1, "R".repeat(550)), rows.get(1));
            assertEquals(List.of(2, "INPLACE_UPDATED"), rows.get(2));
            assertFalse(rows.containsKey(3), "Baris id=3 yang dihapus harus tetap terhapus pasca-recovery");

            for (int id = 1; id <= 15; id++) {
                if (id == 3) {
                    assertTrue(da.searchIndex(INDEX_ID, 3).isEmpty());
                    continue;
                }
                List<RowId> hits = da.searchIndex(INDEX_ID, id);
                assertEquals(1, hits.size(), "Indeks exact untuk id=" + id + " harus konsisten");
                assertEquals(rows.get(id), da.fetchByRowId(hits.get(0)).values());
            }
            assertEquals(14, da.searchIndexRange(INDEX_ID, 1, 15, true, true).size());
        } finally {
            frm.close();
        }
    }

    // AC #5a: Crash di tengah rollback (Runtime.halt) -> restart berikutnya pulih bersih tanpa korupsi
    @Test
    void crashDuringRollbackFollowedBySuccessfulRestartWithoutCorruption() throws Exception {
        Path dbDir = tempDir.resolve("ac5a_crash_during_rollback");
        int exitCode = runChildExpectingHalt(dbDir, "CRASH_DURING_ROLLBACK");
        assertEquals(99, exitCode, "Child JVM harus berhenti paksa via Runtime.halt(99) di tengah rollback");

        Object sm = createRealStorageManager(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
        attachRecoveryManager(sm, frm);
        try {
            assertTrue(frm.recoverOnStartup().isSuccess());
            Map<Integer, List<Object>> rows = readRowsById(da);
            assertEquals(2, rows.size());
            assertEquals(List.of(1, "STABLE_1"), rows.get(1));
            assertEquals(List.of(2, "STABLE_2"), rows.get(2));
            assertFalse(rows.containsKey(30));
            assertEquals(2, da.searchIndexRange(INDEX_ID, 1, 100, true, true).size());
        } finally {
            frm.close();
        }
    }

    // AC #5b: Crash di tengah recovery (double crash via Runtime.halt) -> restart berikutnya sukses tanpa duplikasi
    @Test
    void crashDuringRecoveryFollowedBySuccessfulRestartWithoutDuplicatesOrCorruption() throws Exception {
        Path dbDir = tempDir.resolve("ac5b_crash_during_recovery");
        // Crash #1: tinggalkan campuran committed (NO-FORCE) + uncommitted (STEAL)
        runChildAndKillAtBarrier(dbDir, "PREPARE_DOUBLE_CRASH");

        // Crash #2: jalankan recovery di child JVM yang di-halt paksa tepat di tengah redo/undo
        int haltCode = runChildExpectingHalt(dbDir, "CRASH_MID_RECOVERY");
        assertEquals(99, haltCode, "Child JVM kedua harus terhenti paksa di tengah recovery via halt(99)");

        // Restart #3 (normal): recovery ulang harus idempoten, tanpa duplikasi atau korupsi
        Object sm = createRealStorageManager(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
        attachRecoveryManager(sm, frm);
        try {
            assertTrue(frm.recoverOnStartup().isSuccess());
            Map<Integer, List<Object>> rows = readRowsById(da);
            assertEquals(3, rows.size());
            assertEquals(List.of(1, "COMMITTED_1"), rows.get(1));
            assertEquals(List.of(2, "COMMITTED_2_UPD"), rows.get(2));
            assertEquals(List.of(3, "COMMITTED_3"), rows.get(3));
            assertFalse(rows.containsKey(99));
            assertEquals(3, da.searchIndexRange(INDEX_ID, 1, 100, true, true).size());
        } finally {
            frm.close();
        }
    }

    // AC #6 & #7: Partial/corrupt WAL tail dipangkas sesuai kebijakan & restart kedua tetap valid + terima transaksi baru
    @Test
    void partialCorruptWalTailIsTruncatedAndSecondRestartAcceptsNewTransactions() throws Exception {
        Path dbDir = tempDir.resolve("ac6_ac7_corrupt_tail_second_restart");
        runChildAndKillAtBarrier(dbDir, "CRASH_WITH_PARTIAL_WAL_TAIL");

        // Restart #1: recovery memangkas ekor korup WAL, memulihkan Tx1, lalu mengeksekusi transaksi baru Tx2
        Object sm1 = createRealStorageManager(dbDir);
        IDataAccess da1 = (IDataAccess) sm1;
        IDurableStorage ds1 = (IDurableStorage) sm1;
        FailureRecoveryFacade frm1 = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da1, ds1);
        attachRecoveryManager(sm1, frm1);
        try {
            IConcurrencyControl ccm1 = createLockBasedCcm(frm1);
            invokeInitAfterRecovery(ccm1);

            Map<Integer, List<Object>> afterFirstRestart = readRowsById(da1);
            assertEquals(1, afterFirstRestart.size());
            assertEquals(List.of(1, "BEFORE_CORRUPT_TAIL"), afterFirstRestart.get(1));

            // Terima transaksi baru pasca-restart pertama
            int txNew1 = ccm1.beginTransaction();
            RowId rid2 = da1.reserveRowId(TABLE, List.of(2, "AFTER_FIRST_RESTART"));
            assertTrue(frm1.writeDataLog(txNew1, LogType.INSERT, TABLE, rid2,
                    null, new RecordImage(List.of(2, "AFTER_FIRST_RESTART"))).isSuccess());
            da1.insertAt(rid2, List.of(2, "AFTER_FIRST_RESTART"));
            ccm1.commitTransaction(txNew1);
            ds1.flushBuffer();
        } finally {
            frm1.close();
        }

        // Restart #2: harus membuka database valid yang sama (ekor korup tidak merusak restart kedua) dan terima transaksi baru
        Object sm2 = createRealStorageManager(dbDir);
        IDataAccess da2 = (IDataAccess) sm2;
        IDurableStorage ds2 = (IDurableStorage) sm2;
        FailureRecoveryFacade frm2 = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da2, ds2);
        attachRecoveryManager(sm2, frm2);
        try {
            IConcurrencyControl ccm2 = createLockBasedCcm(frm2);
            invokeInitAfterRecovery(ccm2);

            Map<Integer, List<Object>> afterSecondRestart = readRowsById(da2);
            assertEquals(2, afterSecondRestart.size());
            assertEquals(List.of(1, "BEFORE_CORRUPT_TAIL"), afterSecondRestart.get(1));
            assertEquals(List.of(2, "AFTER_FIRST_RESTART"), afterSecondRestart.get(2));

            // Pastikan database pada restart kedua tetap menerima transaksi baru secara normal
            int txNew2 = ccm2.beginTransaction();
            RowId rid3 = da2.reserveRowId(TABLE, List.of(3, "AFTER_SECOND_RESTART"));
            assertTrue(frm2.writeDataLog(txNew2, LogType.INSERT, TABLE, rid3,
                    null, new RecordImage(List.of(3, "AFTER_SECOND_RESTART"))).isSuccess());
            da2.insertAt(rid3, List.of(3, "AFTER_SECOND_RESTART"));
            ccm2.commitTransaction(txNew2);
            ds2.flushBuffer();

            assertEquals(3, readRowsById(da2).size());
            assertEquals(3, da2.searchIndexRange(INDEX_ID, 1, 10, true, true).size());
        } finally {
            frm2.close();
        }
    }

    // --- Child JVM Process Launcher ---

    private static void runChildAndKillAtBarrier(Path dbDir, String scenario) throws Exception {
        Files.createDirectories(dbDir);
        Process proc = startWorkerProcess(dbDir, scenario);
        StringBuilder output = new StringBuilder();
        boolean barrierReached = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHILD_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                output.append(line).append('\n');
                if (line.startsWith(BARRIER_PREFIX)) {
                    barrierReached = true;
                    // Forced termination tanpa shutdown hooks!
                    proc.destroyForcibly();
                    break;
                }
            }
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
            assertTrue(proc.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Child JVM gagal berhenti setelah destroyForcibly");
        }
        assertTrue(barrierReached, "Child JVM gagal mencapai crash barrier untuk " + scenario + ". Output:\n" + output);
    }

    private static int runChildExpectingHalt(Path dbDir, String scenario) throws Exception {
        Files.createDirectories(dbDir);
        Process proc = startWorkerProcess(dbDir, scenario);
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean exited = proc.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
        }
        assertTrue(exited, "Child JVM timeout pada skenario " + scenario + ". Output:\n" + output);
        int code = proc.exitValue();
        assertNotEquals(0, code, "Child JVM seharusnya terhenti paksa, bukan exit 0. Output:\n" + output);
        return code;
    }

    private static Process startWorkerProcess(Path dbDir, String scenario) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();

        List<String> cpEntries = new ArrayList<>();
        cpEntries.add(System.getProperty("java.class.path"));
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String candidate : new String[] {
            "mdbms-storage/target/classes", "../mdbms-storage/target/classes",
            "mdbms-concurrency/target/classes", "../mdbms-concurrency/target/classes"
        }) {
            Path p = cwd.resolve(candidate).normalize();
            if (Files.exists(p)) {
                cpEntries.add(p.toString());
            }
        }
        String classpath = String.join(File.pathSeparator, cpEntries);

        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, Worker.class.getName(), dbDir.toAbsolutePath().toString(), scenario);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    // --- Child JVM Worker (dijalankan di proses terpisah) ---

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            Path dbDir = Path.of(args[0]);
            String scenario = args[1];

            switch (scenario) {
                case "CRASH_BEFORE_LOGGING" -> runCrashBeforeLogging(dbDir);
                case "CRASH_AFTER_LOGGING_BEFORE_MUTATION" -> runCrashAfterLoggingBeforeMutation(dbDir);
                case "CRASH_AFTER_UNCOMMITTED_FLUSH" -> runCrashAfterUncommittedFlush(dbDir);
                case "CRASH_AFTER_COMMIT_NO_FLUSH" -> runCrashAfterCommitNoFlush(dbDir);
                case "CRASH_FULL_HISTORY_NO_FLUSH" -> runCrashFullHistoryNoFlush(dbDir);
                case "CRASH_DURING_ROLLBACK" -> runCrashDuringRollback(dbDir);
                case "PREPARE_DOUBLE_CRASH" -> runPrepareDoubleCrash(dbDir);
                case "CRASH_MID_RECOVERY" -> runCrashMidRecovery(dbDir);
                case "CRASH_WITH_PARTIAL_WAL_TAIL" -> runCrashWithPartialWalTail(dbDir);
                default -> throw new IllegalArgumentException("Skenario tidak dikenal: " + scenario);
            }
        }

        private static void signalBarrierAndBlock(String name) throws InterruptedException {
            System.out.println(BARRIER_PREFIX + name);
            System.out.flush();
            Thread.sleep(60_000L);
        }

        private static Object openInitializedStorage(Path dbDir) throws Exception {
            Class<?> smClass = Class.forName("mdbms.storage.StorageManager");
            Object sm = smClass.getConstructor(String.class, int.class, int.class)
                    .newInstance(dbDir.toString(), BUFFER_CAPACITY, INDEX_MAX_DEGREE);
            ISchemaCatalog catalog = (ISchemaCatalog) sm;
            if (catalog.getTableSchema(TABLE) == null) {
                TableSchema schema = new TableSchema(
                        TABLE,
                        List.of(
                                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                                new ColumnDefinition("payload", DataType.VARCHAR, 600, false)
                        ),
                        List.of("id"),
                        List.of()
                );
                catalog.updateTableSchema(schema);
            }
            if (catalog.getIndexes(TABLE).stream().noneMatch(i -> INDEX_ID.equals(i.indexName()))) {
                catalog.createIndex(INDEX_ID, TABLE, "id");
            }
            return sm;
        }

        private static void attachFrm(Object sm, IFailureRecoveryManager frm) throws Exception {
            sm.getClass().getMethod("attachRecoveryManager", IFailureRecoveryManager.class).invoke(sm, frm);
        }

        private static RowId insertLogged(FailureRecoveryFacade frm, IDataAccess da, int txId, List<Object> vals) {
            RowId rid = da.reserveRowId(TABLE, vals);
            RecoveryResult r = frm.writeDataLog(txId, LogType.INSERT, TABLE, rid, null, new RecordImage(vals));
            if (!r.isSuccess()) throw new IllegalStateException(r.getMessage());
            da.insertAt(rid, vals);
            return rid;
        }

        private static void runCrashBeforeLogging(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess da = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
            attachFrm(sm, frm);

            frm.writeTransactionLog(1, LogType.BEGIN);
            insertLogged(frm, da, 1, List.of(1, "SEED_ROW"));
            frm.writeTransactionLog(1, LogType.COMMIT);
            ds.flushBuffer();

            // Tx2: BEGIN + reserveRowId, BELUM ada data log, flush buffer agar orphan reservation ikut ke disk
            frm.writeTransactionLog(2, LogType.BEGIN);
            frm.flushLogUpTo(frm.getLogManager().getCurrentLsn() - 1);
            da.reserveRowId(TABLE, List.of(2, "NEVER_LOGGED"));
            ds.flushBuffer();
            signalBarrierAndBlock("CRASH_BEFORE_LOGGING");
        }

        private static void runCrashAfterLoggingBeforeMutation(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess da = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
            attachFrm(sm, frm);

            frm.writeTransactionLog(1, LogType.BEGIN);
            insertLogged(frm, da, 1, List.of(1, "SEED_ROW"));
            frm.writeTransactionLog(1, LogType.COMMIT);
            ds.flushBuffer();

            // Tx2: durable INSERT + UPDATE log tertulis ke WAL, tetapi storage BELUM dimutasi sama sekali
            frm.writeTransactionLog(2, LogType.BEGIN);
            RowId reserved = da.reserveRowId(TABLE, List.of(2, "LOGGED_ONLY"));
            frm.writeDataLog(2, LogType.INSERT, TABLE, reserved, null, new RecordImage(List.of(2, "LOGGED_ONLY")));
            RecoveryResult u = frm.writeDataLog(2, LogType.UPDATE, TABLE, reserved,
                    new RecordImage(List.of(2, "LOGGED_ONLY")),
                    new RecordImage(List.of(2, "LOGGED_ONLY_V2")));
            frm.flushLogUpTo(u.getLsn());
            signalBarrierAndBlock("CRASH_AFTER_LOGGING_BEFORE_MUTATION");
        }

        private static void runCrashAfterUncommittedFlush(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess da = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
            attachFrm(sm, frm);

            frm.writeTransactionLog(1, LogType.BEGIN);
            RowId r1 = insertLogged(frm, da, 1, List.of(1, "SEED_ONE"));
            RowId r2 = insertLogged(frm, da, 1, List.of(2, "SEED_TWO"));
            frm.writeTransactionLog(1, LogType.COMMIT);
            ds.flushBuffer();

            // Tx2 (uncommitted): UPDATE r1, DELETE r2, INSERT r3 -> paksa WAL & flush dirty pages ke disk (STEAL)
            frm.writeTransactionLog(2, LogType.BEGIN);
            frm.writeDataLog(2, LogType.UPDATE, TABLE, r1,
                    new RecordImage(List.of(1, "SEED_ONE")), new RecordImage(List.of(1, "UNCOMMITTED_DIRTY_UPDATE")));
            da.updateByRowId(r1, List.of(1, "UNCOMMITTED_DIRTY_UPDATE"));

            frm.writeDataLog(2, LogType.DELETE, TABLE, r2, new RecordImage(List.of(2, "SEED_TWO")), null);
            da.deleteByRowId(r2);

            insertLogged(frm, da, 2, List.of(3, "UNCOMMITTED_INSERT"));
            frm.flushLogUpTo(frm.getLogManager().getCurrentLsn() - 1);
            ds.flushBuffer();
            signalBarrierAndBlock("CRASH_AFTER_UNCOMMITTED_FLUSH");
        }

        private static void runCrashAfterCommitNoFlush(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess da = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
            attachFrm(sm, frm);

            // NO-FORCE: tulis INSERT + UPDATE + durable COMMIT, TANPA flushBuffer()
            frm.writeTransactionLog(1, LogType.BEGIN);
            insertLogged(frm, da, 1, List.of(10, "NOFORCE_COMMITTED_PAYLOAD"));
            RowId r11 = insertLogged(frm, da, 1, List.of(11, "TEMP_11"));
            frm.writeDataLog(1, LogType.UPDATE, TABLE, r11,
                    new RecordImage(List.of(11, "TEMP_11")),
                    new RecordImage(List.of(11, "NOFORCE_UPDATED_PAYLOAD")));
            da.updateByRowId(r11, List.of(11, "NOFORCE_UPDATED_PAYLOAD"));
            // COMMIT otomatis mem-force WAL di LogManager; tidak ada flushBuffer() dan belum kirim ack ke client
            frm.writeTransactionLog(1, LogType.COMMIT);
            signalBarrierAndBlock("CRASH_AFTER_COMMIT_NO_FLUSH");
        }

        private static void runCrashFullHistoryNoFlush(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess da = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
            attachFrm(sm, frm);

            // Tx1: isi halaman 0 penuh agar id=1 ("short") terpaksa pindah blok saat di-update ke payload 550 byte
            frm.writeTransactionLog(1, LogType.BEGIN);
            RowId r1 = insertLogged(frm, da, 1, List.of(1, "short"));
            RowId r2 = insertLogged(frm, da, 1, List.of(2, "F".repeat(450)));
            RowId r3 = insertLogged(frm, da, 1, List.of(3, "F".repeat(450)));
            for (int id = 4; id <= 15; id++) {
                insertLogged(frm, da, 1, List.of(id, "F".repeat(450)));
            }
            frm.writeTransactionLog(1, LogType.COMMIT);

            // Tx2: in-place UPDATE (id=2), relocating UPDATE (id=1), DELETE (id=3) -> COMMIT tanpa flushBuffer()
            frm.writeTransactionLog(2, LogType.BEGIN);
            frm.writeDataLog(2, LogType.UPDATE, TABLE, r2,
                    new RecordImage(List.of(2, "F".repeat(450))),
                    new RecordImage(List.of(2, "INPLACE_UPDATED")));
            da.updateInPlace(r2, List.of(2, "INPLACE_UPDATED"));

            List<Object> relocatedVals = List.of(1, "R".repeat(550));
            RowId newR1 = da.reserveRowId(TABLE, relocatedVals);
            frm.writeRelocationLog(2, TABLE, r1, newR1,
                    new RecordImage(List.of(1, "short")), new RecordImage(relocatedVals));
            da.insertAt(newR1, relocatedVals);
            da.deleteByRowId(r1);

            frm.writeDataLog(2, LogType.DELETE, TABLE, r3,
                    new RecordImage(List.of(3, "F".repeat(450))), null);
            da.deleteByRowId(r3);

            frm.writeTransactionLog(2, LogType.COMMIT);
            signalBarrierAndBlock("CRASH_FULL_HISTORY_NO_FLUSH");
        }

        private static void runCrashDuringRollback(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess rawDa = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            CrashingDataAccess proxyDa = new CrashingDataAccess(rawDa);
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), proxyDa, ds);
            attachFrm(sm, frm);

            frm.writeTransactionLog(1, LogType.BEGIN);
            RowId r1 = insertLogged(frm, rawDa, 1, List.of(1, "STABLE_1"));
            RowId r2 = insertLogged(frm, rawDa, 1, List.of(2, "STABLE_2"));
            frm.writeTransactionLog(1, LogType.COMMIT);
            ds.flushBuffer();

            // Tx2 uncommitted dengan 3 mutasi yang sudah ter-flush ke disk
            frm.writeTransactionLog(2, LogType.BEGIN);
            frm.writeDataLog(2, LogType.UPDATE, TABLE, r1,
                    new RecordImage(List.of(1, "STABLE_1")), new RecordImage(List.of(1, "ROLLBACK_CRASH_1")));
            rawDa.updateByRowId(r1, List.of(1, "ROLLBACK_CRASH_1"));
            frm.writeDataLog(2, LogType.UPDATE, TABLE, r2,
                    new RecordImage(List.of(2, "STABLE_2")), new RecordImage(List.of(2, "ROLLBACK_CRASH_2")));
            rawDa.updateByRowId(r2, List.of(2, "ROLLBACK_CRASH_2"));
            insertLogged(frm, rawDa, 2, List.of(30, "ROLLBACK_CRASH_3"));
            frm.flushLogUpTo(frm.getLogManager().getCurrentLsn() - 1);
            ds.flushBuffer();

            // Pasang pemicu halt(99) tepat setelah 1 operasi undo pertama pada rollback
            proxyDa.armCrashAfterMutations(1);
            frm.rollbackTransaction(2);
        }

        private static void runPrepareDoubleCrash(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess da = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
            attachFrm(sm, frm);

            // Tx1 committed (NO-FORCE sebagian)
            frm.writeTransactionLog(1, LogType.BEGIN);
            insertLogged(frm, da, 1, List.of(1, "COMMITTED_1"));
            RowId r2 = insertLogged(frm, da, 1, List.of(2, "COMMITTED_2"));
            frm.writeDataLog(1, LogType.UPDATE, TABLE, r2,
                    new RecordImage(List.of(2, "COMMITTED_2")), new RecordImage(List.of(2, "COMMITTED_2_UPD")));
            da.updateByRowId(r2, List.of(2, "COMMITTED_2_UPD"));
            insertLogged(frm, da, 1, List.of(3, "COMMITTED_3"));
            frm.writeTransactionLog(1, LogType.COMMIT);

            // Tx2 uncommitted (STEAL flushed)
            frm.writeTransactionLog(2, LogType.BEGIN);
            insertLogged(frm, da, 2, List.of(99, "UNCOMMITTED_99"));
            frm.flushLogUpTo(frm.getLogManager().getCurrentLsn() - 1);
            ds.flushBuffer();
            signalBarrierAndBlock("PREPARE_DOUBLE_CRASH");
        }

        private static void runCrashMidRecovery(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess rawDa = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            CrashingDataAccess proxyDa = new CrashingDataAccess(rawDa);
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), proxyDa, ds);
            attachFrm(sm, frm);

            // Hentikan paksa tepat setelah 2 mutasi redo/undo selama recoverOnStartup()
            proxyDa.armCrashAfterMutations(2);
            frm.recoverOnStartup();
        }

        private static void runCrashWithPartialWalTail(Path dbDir) throws Exception {
            Object sm = openInitializedStorage(dbDir);
            IDataAccess da = (IDataAccess) sm;
            IDurableStorage ds = (IDurableStorage) sm;
            FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, dbDir.resolve("snapshot"), da, ds);
            attachFrm(sm, frm);

            frm.writeTransactionLog(1, LogType.BEGIN);
            insertLogged(frm, da, 1, List.of(1, "BEFORE_CORRUPT_TAIL"));
            frm.writeTransactionLog(1, LogType.COMMIT);

            // Simulasikan penulisan baris WAL terakhir yang terpotong saat proses mati mendadak (tanpa newline)
            Path walFile = dbDir.resolve(WalFileHandler.DEFAULT_WAL_PATH);
            Files.writeString(walFile, "99|5|UPDATE|2026-11-21T00:00:00Z|produk|0",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            signalBarrierAndBlock("CRASH_WITH_PARTIAL_WAL_TAIL");
        }
    }

    /** Proxy IDataAccess untuk memicu Runtime.getRuntime().halt(99) secara deterministik di tengah rollback/recovery. */
    private static final class CrashingDataAccess implements IDataAccess {
        private final IDataAccess delegate;
        private final AtomicInteger remainingBeforeHalt = new AtomicInteger(-1);

        private CrashingDataAccess(IDataAccess delegate) {
            this.delegate = delegate;
        }

        void armCrashAfterMutations(int mutations) {
            remainingBeforeHalt.set(mutations);
        }

        private void tickMutation() {
            int rem = remainingBeforeHalt.get();
            if (rem > 0 && remainingBeforeHalt.decrementAndGet() == 0) {
                Runtime.getRuntime().halt(99);
            }
        }

        @Override public Rows readBlock(DataRetrieval request) { return delegate.readBlock(request); }
        @Override public List<RowId> writeBlock(DataWrite request) { return delegate.writeBlock(request); }
        @Override public int deleteBlock(DataDeletion request) { return delegate.deleteBlock(request); }
        @Override public Row fetchByRowId(RowId rowId) { return delegate.fetchByRowId(rowId); }
        @Override public RowId reserveRowId(String table, List<Object> values) { return delegate.reserveRowId(table, values); }
        @Override public void releaseRowId(RowId rowId) { delegate.releaseRowId(rowId); }

        @Override
        public void insertAt(RowId rowId, List<Object> values) {
            delegate.insertAt(rowId, values);
            tickMutation();
        }

        @Override
        public Row updateByRowId(RowId rowId, List<Object> values) {
            Row updated = delegate.updateByRowId(rowId, values);
            tickMutation();
            return updated;
        }

        @Override
        public Row deleteByRowId(RowId rowId) {
            Row deleted = delegate.deleteByRowId(rowId);
            tickMutation();
            return deleted;
        }

        @Override public List<RowId> searchIndex(String indexName, Object key) { return delegate.searchIndex(indexName, key); }
        @Override public List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInc, boolean highInc) {
            return delegate.searchIndexRange(indexName, low, high, lowInc, highInc);
        }
    }

    // --- Helper Parent JVM ---

    private static Map<Integer, List<Object>> readRowsById(IDataAccess da) {
        Map<Integer, List<Object>> map = new HashMap<>();
        Rows rows = da.readBlock(new DataRetrieval(TABLE, List.of(), List.of()));
        if (rows != null && rows.rows() != null) {
            for (Row r : rows.rows()) {
                if (r != null && r.values() != null && !r.values().isEmpty()) {
                    map.put((Integer) r.values().get(0), r.values());
                }
            }
        }
        return map;
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
            siblingClassLoader = new URLClassLoader(urls.toArray(new URL[0]), CrashRecoveryIT.class.getClassLoader());
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

    private static void attachRecoveryManager(Object sm, IFailureRecoveryManager frm) {
        try {
            Method m = sm.getClass().getMethod("attachRecoveryManager", IFailureRecoveryManager.class);
            m.invoke(sm, frm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int invokeReleaseOrphanReservations(Object sm) {
        try {
            return (int) sm.getClass().getMethod("releaseOrphanReservations").invoke(sm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static IConcurrencyControl createLockBasedCcm(IFailureRecoveryManager frm) {
        try {
            ClassLoader cl = getSiblingClassLoader();
            Class<?> protocolInterface = Class.forName("mdbms.concurrency.ConcurrencyProtocol", true, cl);
            Object protocol = Class.forName("mdbms.concurrency.LockBasedProtocol", true, cl)
                    .getDeclaredConstructor().newInstance();
            Class<?> ccmClass = Class.forName("mdbms.concurrency.ConcurrencyControlManager", true, cl);
            return (IConcurrencyControl) ccmClass.getConstructor(protocolInterface, IFailureRecoveryManager.class)
                    .newInstance(protocol, frm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat ConcurrencyControlManager", e);
        }
    }

    private static void invokeInitAfterRecovery(IConcurrencyControl ccm) {
        try {
            ccm.getClass().getMethod("initAfterRecovery").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}