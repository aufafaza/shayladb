package mdbms.recovery;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.AccessStatus;

/**
 * Driver Program Komponen mandiri untuk menguji integrasi Failure Recovery Manager
 * (FailureRecoveryFacade) murni melalui kontrak interface mdbms-api (IDataAccess, IDurableStorage, 
 * ISchemaCatalog, dan IConcurrencyControl) yang dihubungkan ke implementasi asli StorageManager 
 * dan ConcurrencyControlManager saat runtime.
 *
 * Dokumentasi Cara Menjalankan Driver & Simulasi Crash:
 * 1. Kompilasi seluruh program dari root directory:
 *      mvn test-compile
 * 2. Jalankan FRMTestDriver.main() secara langsung melalui IDE atau CLI:
 *      CLI: java -cp "mdbms-recovery/target/classes;mdbms-recovery/target/test-classes;mdbms-api/target/classes;mdbms-models/target/classes;mdbms-storage/target/classes;mdbms-concurrency/target/classes" mdbms.recovery.FRMTestDriver
 *      IDE: Buka file FRMTestDriver.java, lalu klik tombol "Run Java" (ikon Play)
 * 3. Penyiapan Data:
 *      Setiap skenario otomatis membersihkan dan menyiapkan sub-folder kerja mandiri
 *      di dalam "build/frm_test_workspace" beserta skema tabel "mahasiswa" melalui ISchemaCatalog
 *      sehingga pengujian selalu dapat dijalankan ulang dari keadaan awal yang diketahui
 *      tanpa perlu mengubah kode.
 * 4. Mekanisme Simulasi Crash:
 *      Method runSimulatedCrashScenario() dan runSimulatedCrashWithCheckpointScenario()
 *      melahirkan Child Process JVM terpisah via ProcessBuilder dengan flag:
 *        --crash-worker-no-cp <dir>
 *        --crash-worker-with-cp <dir>
 *      Child process mengeksekusi transaksi lalu menghentikan proses secara paksa menggunakan
 *      Runtime.getRuntime().halt(99) (tanpa graceful shutdown). Proses utama kemudian membuat 
 *      instance StorageManager, FailureRecoveryFacade, dan ConcurrencyControlManager baru 
 *      untuk memverifikasi hasil recoverOnStartup().
 */
public class FRMTestDriver {

    private static final String TABLE = "mahasiswa";
    private static final int BUFFER_CAPACITY = 32;
    private static final int INDEX_MAX_DEGREE = 4;

    private static ClassLoader siblingClassLoader;

    private IFailureRecoveryManager facade;
    private IDataAccess dataAccess;
    private IDurableStorage durableStorage;
    private IConcurrencyControl ccm;
    private final Path testDir;
    private final List<ScenarioReport> reports = new ArrayList<>();

    public FRMTestDriver(
        Path testDir,
        IFailureRecoveryManager facade,
        IDataAccess dataAccess,
        IDurableStorage durableStorage,
        IConcurrencyControl ccm
    ) {
        this.testDir = testDir;
        this.facade = facade;
        this.dataAccess = dataAccess;
        this.durableStorage = durableStorage;
        this.ccm = ccm;
    }

    public static void main(String[] args) throws Exception {
        // Entry point child process untuk simulasi crash
        if (args.length >= 2 && "--crash-worker-no-cp".equals(args[0])) {
            executeCrashWorkerWithoutCheckpoint(Path.of(args[1]));
            return;
        }
        if (args.length >= 2 && "--crash-worker-with-cp".equals(args[0])) {
            executeCrashWorkerWithCheckpoint(Path.of(args[1]));
            return;
        }

        Path baseDir = Path.of("build", "frm_test_workspace");
        cleanDirectory(baseDir);

        FRMTestDriver driver = new FRMTestDriver(baseDir, null, null, null, null);
        System.out.println("================================================================================");
        System.out.println("                     FAILURE RECOVERY MANAGER TEST DRIVER                       ");
        System.out.println("================================================================================");

        boolean s1 = driver.runMultiTxInterleavedScenario();
        boolean s2 = driver.runSimulatedCrashScenario();
        boolean s3 = driver.runSimulatedCrashWithCheckpointScenario();
        boolean s4 = driver.runPitrScenario();

        driver.printTestReport();

        if (!(s1 && s2 && s3 && s4)) {
            System.exit(1);
        }
    }

    /**
     * Skenario 1: Eksekusi Multi-Transaksi Konkuren
     *
     * Urutan Kejadian:
     * - t0:
     *   T1 BEGIN
     *   T1 INSERT R1 (101, Andi, 100)
     *   T1 INSERT R2 (102, Budi, 200)
     * - t1:
     *   T2 BEGIN
     *   T2 INSERT R3 (103, Citra, 100)
     *   T2 UPDATE R3 (103, Citra, 120)
     * - t2:
     *   T3 BEGIN
     *   T3 READ R1 (101, Andi, 100) -> Ditahan oleh CCM karena T1 belum commit
     * - t3:
     *   T1 COMMIT -> Log di-flush ke wal.log, lock R1 dan R2 dilepas
     * - t4:
     *   T3 UPDATE R1 (101, Andi, 150)
     * - t5:
     *   T2 UPDATE R3 (103, Citra, 180)
     *   T2 ABORT -> RollbackManager menjalankan Undo berantai pada R3 dan mencatat log ABORT
     * - t6:
     *   T3 DELETE R2 (102, Budi, 200)
     *   T3 COMMIT
     * - t7:
     *   Verifikasi pembacaan log ABORT milik T2 dari wal.log
     *
     * Kondisi Akhir yang Diharapkan (Expected):
     * - Data R1 (101, Andi, 150) ada di tabel
     * - Data R2 (102, Budi) tidak ada (null) karena dihapus oleh T3
     * - Data R3 (103, Citra) tidak ada (null) karena T2 di-rollback
     */
    public boolean runMultiTxInterleavedScenario() {
        Path scenarioDir = testDir.resolve("scenario_1_interleaved");
        cleanDirectory(scenarioDir);

        Object smInstance = createInitializedStorage(scenarioDir);
        IDataAccess smData = (IDataAccess) smInstance;
        IDurableStorage smDurable = (IDurableStorage) smInstance;

        FailureRecoveryFacade frm = new FailureRecoveryFacade(
            scenarioDir,
            scenarioDir.resolve("snapshot"),
            smData,
            smDurable
        );
        attachRecoveryManagerToStorage(smInstance, frm);
        IConcurrencyControl activeCcm = createRealConcurrencyControl(frm);
        invokeCcmInitAfterRecovery(activeCcm);

        this.dataAccess = smData;
        this.durableStorage = smDurable;
        this.facade = frm;
        this.ccm = activeCcm;

        try {
            // t0: T1 BEGIN, T1 INSERT R1 (101, Andi, 100), dan T1 INSERT R2 (102, Budi, 200)
            int t1 = activeCcm.beginTransaction();
            RowId r1 = execInsert(frm, smData, activeCcm, t1, List.of(101, "Andi", 100));
            RowId r2 = execInsert(frm, smData, activeCcm, t1, List.of(102, "Budi", 200));

            // t1: T2 BEGIN, T2 INSERT R3 (103, Citra, 100), dan T2 UPDATE R3 (103, Citra, 120)
            int t2 = activeCcm.beginTransaction();
            RowId r3 = execInsert(frm, smData, activeCcm, t2, List.of(103, "Citra", 100));
            execUpdate(frm, smData, activeCcm, t2, r3, List.of(103, "Citra", 100), List.of(103, "Citra", 120));

            // t2: T3 BEGIN dan T3 READ R1 (101, Andi, 100) yang masih dikunci oleh T1
            int t3 = activeCcm.beginTransaction();
            boolean blockedByCcm = isConflictingAccessBlocked(activeCcm, t3, r1);

            // t3: T1 COMMIT dan melepas lock R1 serta R2
            activeCcm.commitTransaction(t1);

            // Buat transaksi pengganti jika protokol CCM menggugurkan T3 saat konflik
            if (activeCcm.getTransaction(t3) == null) {
                t3 = activeCcm.beginTransaction();
            }

            // t4: T1 sudah melepas lock, T3 UPDATE R1 (101, Andi, 150)
            execUpdate(frm, smData, activeCcm, t3, r1, List.of(101, "Andi", 100), List.of(101, "Andi", 150));

            // t5: T2 UPDATE R3 (103, Citra, 180) lalu T2 ABORT
            execUpdate(frm, smData, activeCcm, t2, r3, List.of(103, "Citra", 120), List.of(103, "Citra", 180));
            activeCcm.abortTransaction(t2, "Simulasi pembatalan transaksi T2");

            // t6: T3 DELETE R2 (102, Budi, 200) lalu T3 COMMIT
            execDelete(frm, smData, activeCcm, t3, r2, List.of(102, "Budi", 200));
            activeCcm.commitTransaction(t3);
            smDurable.flushBuffer();

            // t7: Verifikasi catatan ABORT milik T2 tersimpan di WAL
            List<LogRecord> t2Logs = frm.readLogs(t2);
            boolean t2HasAbortLog = !t2Logs.isEmpty()
                && t2Logs.get(t2Logs.size() - 1).getType() == LogType.ABORT;

            Map<Integer, List<Object>> currentRows = readAllRowsByNim(smData);

            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("BlockedByCCMWhileUncommitted", true);
            expected.put("T2AbortLogRecorded", true);
            expected.put("Row_101_Andi", List.of(101, "Andi", 150));
            expected.put("Row_102_Budi_Deleted", null);
            expected.put("Row_103_Citra_RolledBack", null);

            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("BlockedByCCMWhileUncommitted", blockedByCcm);
            actual.put("T2AbortLogRecorded", t2HasAbortLog);
            actual.put("Row_101_Andi", currentRows.get(101));
            actual.put("Row_102_Budi_Deleted", currentRows.get(102));
            actual.put("Row_103_Citra_RolledBack", currentRows.get(103));

            boolean passed = Objects.equals(expected, actual);
            reports.add(new ScenarioReport(
                "Skenario 1: Multi-Tx Interleaved, CCM Lock Isolation & Runtime Rollback",
                expected.toString(),
                actual.toString(),
                passed
            ));
            return passed;
        } finally {
            frm.close();
        }
    }

    /**
     * Skenario 2: Simulasi Crash Nyata Tanpa Checkpoint
     *
     * Urutan Kejadian:
     * - t0 (di Child Process):
     *   T2 BEGIN
     *   T2 INSERT R2 (102, T2_Temp, 50)
     *   T2 ABORT -> Perubahan di-Undo dan log ABORT tercatat di wal.log
     * - t1 (di Child Process):
     *   T3 BEGIN
     *   T3 INSERT R3 (103, T3_Dirty1, 300)
     *   T3 UPDATE R3 (103, T3_Dirty2, 999)
     *   BufferPool di-flush ke disk meskipun T3 belum commit
     * - t2 (di Child Process):
     *   T1 BEGIN
     *   T1 INSERT R1 (101, T1_Init, 100)
     *   T1 UPDATE R1 (101, T1_Final, 200)
     *   T1 COMMIT -> Log di-flush ke wal.log tanpa flush BufferPool ke disk
     * - t3 (di Child Process):
     *   CRASH -> Proses dihentikan paksa dengan Runtime.getRuntime().halt(99)
     * - t4 (di Parent Process):
     *   Eksekusi recoverOnStartup() pada instance baru:
     *   T1 berstatus committed -> Perubahan di-Redo ke disk
     *   T2 berstatus aborted -> Diabaikan karena sudah di-rollback sebelum crash
     *   T3 berstatus active -> Perubahan di-Undo mundur dan dicatat log ABORT
     * - t5 (di Parent Process):
     *   Restart kedua pada instance baru untuk menguji idempotensi hasil recovery
     *
     * Kondisi Akhir yang Diharapkan (Expected):
     * - Data R1 (101, T1_Final, 200) ada di tabel (hasil Redo T1)
     * - Data R2 (102, T2_Temp) tidak ada (null) karena T2 sudah abort sebelum crash
     * - Data R3 (103, T3_Dirty2) tidak ada (null) karena T3 di-Undo saat recovery
     */
    public boolean runSimulatedCrashScenario() {
        Path scenarioDir = testDir.resolve("scenario_2_crash_no_cp");
        cleanDirectory(scenarioDir);

        // t0 - t3: Jalankan child process untuk eksekusi transaksi hingga crash (halt 99)
        int exitCode = spawnCrashWorker("--crash-worker-no-cp", scenarioDir);

        // t4: Jalankan recovery pasca-crash pada instance baru
        Object smAfterCrash = createRealStorageManager(scenarioDir);
        FailureRecoveryFacade frmAfterCrash = new FailureRecoveryFacade(
            scenarioDir,
            scenarioDir.resolve("snapshot"),
            (IDataAccess) smAfterCrash,
            (IDurableStorage) smAfterCrash
        );
        attachRecoveryManagerToStorage(smAfterCrash, frmAfterCrash);
        IConcurrencyControl ccmAfterCrash = createRealConcurrencyControl(frmAfterCrash);
        invokeCcmInitAfterRecovery(ccmAfterCrash);
        frmAfterCrash.close();

        // t5: Restart kedua untuk menguji idempotensi hasil recovery
        Object smSecondRestart = createRealStorageManager(scenarioDir);
        IDataAccess smDataSecond = (IDataAccess) smSecondRestart;
        FailureRecoveryFacade frmSecondRestart = new FailureRecoveryFacade(
            scenarioDir,
            scenarioDir.resolve("snapshot"),
            smDataSecond,
            (IDurableStorage) smSecondRestart
        );
        attachRecoveryManagerToStorage(smSecondRestart, frmSecondRestart);
        IConcurrencyControl ccmSecondRestart = createRealConcurrencyControl(frmSecondRestart);
        invokeCcmInitAfterRecovery(ccmSecondRestart);

        try {
            Map<Integer, List<Object>> rowsAfterRecovery = readAllRowsByNim(smDataSecond);

            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("CrashHaltExitCode", 99);
            expected.put("CCMRecovered", true);
            expected.put("Row_101_CommittedRedo", List.of(101, "T1_Final", 200));
            expected.put("Row_102_Aborted", null);
            expected.put("Row_103_UncommittedUndo", null);

            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("CrashHaltExitCode", exitCode);
            actual.put("CCMRecovered", invokeCcmIsRecovered(ccmSecondRestart));
            actual.put("Row_101_CommittedRedo", rowsAfterRecovery.get(101));
            actual.put("Row_102_Aborted", rowsAfterRecovery.get(102));
            actual.put("Row_103_UncommittedUndo", rowsAfterRecovery.get(103));

            boolean passed = Objects.equals(expected, actual);
            reports.add(new ScenarioReport(
                "Skenario 2: Crash Nyata Tanpa Checkpoint (Redo Committed & Undo Uncommitted)",
                expected.toString(),
                actual.toString(),
                passed
            ));
            return passed;
        } finally {
            frmSecondRestart.close();
        }
    }

    /**
     * Skenario 3: Simulasi Crash Nyata Dengan Checkpoint
     *
     * Urutan Kejadian:
     * - t0 (di Child Process - Sebelum Checkpoint):
     *   T1 BEGIN
     *   T1 INSERT R1 (101, R1_AtCP, 100)
     *   T1 INSERT R2 (102, R2_AtCP, 200)
     *   T1 COMMIT
     * - t1 (di Child Process - Saat Checkpoint):
     *   CCM menahan transaksi baru dan menunggu transaksi aktif selesai
     *   FRM mem-flush WAL, mem-flush BufferPool ke disk, dan mencatat log CHECKPOINT
     * - t2 (di Child Process - Setelah Checkpoint):
     *   T3 BEGIN
     *   T3 UPDATE R2 (102, R2_Dirty, 888)
     *   T3 DELETE R2 (102, R2_Dirty, 888)
     *   BufferPool di-flush ke disk meskipun T3 belum commit
     * - t3 (di Child Process - Setelah Checkpoint):
     *   T2 BEGIN
     *   T2 UPDATE R1 (101, R1_AfterCP, 555)
     *   T2 COMMIT -> Log di-flush ke wal.log tanpa flush BufferPool ke disk
     * - t4 (di Child Process):
     *   CRASH -> Proses dihentikan paksa dengan Runtime.getRuntime().halt(99)
     * - t5 (di Parent Process):
     *   Eksekusi recoverOnStartup() dari titik CHECKPOINT terakhir pada instance baru:
     *   T2 berstatus committed -> Perubahan di-Redo ke disk
     *   T3 berstatus active -> Perubahan di-Undo mundur hingga kembali ke kondisi saat checkpoint
     *
     * Kondisi Akhir yang Diharapkan (Expected):
     * - Data R1 (101, R1_AfterCP, 555) ada di tabel (hasil Redo T2 pasca-checkpoint)
     * - Data R2 (102, R2_AtCP, 200) ada di tabel (pulih ke nilai saat checkpoint setelah T3 di-Undo)
     */
    public boolean runSimulatedCrashWithCheckpointScenario() {
        Path scenarioDir = testDir.resolve("scenario_3_crash_with_cp");
        cleanDirectory(scenarioDir);

        // t0 - t4: Jalankan child process untuk eksekusi transaksi dan checkpoint hingga crash (halt 99)
        int exitCode = spawnCrashWorker("--crash-worker-with-cp", scenarioDir);

        // t5: Jalankan recovery dari checkpoint terakhir pada instance baru
        Object smRecovered = createRealStorageManager(scenarioDir);
        IDataAccess smData = (IDataAccess) smRecovered;
        FailureRecoveryFacade frmRecovered = new FailureRecoveryFacade(
            scenarioDir,
            scenarioDir.resolve("snapshot"),
            smData,
            (IDurableStorage) smRecovered
        );
        attachRecoveryManagerToStorage(smRecovered, frmRecovered);
        IConcurrencyControl ccmRecovered = createRealConcurrencyControl(frmRecovered);
        invokeCcmInitAfterRecovery(ccmRecovered);

        try {
            Map<Integer, List<Object>> rows = readAllRowsByNim(smData);

            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("CrashHaltExitCode", 99);
            expected.put("CCMRecovered", true);
            expected.put("Row_101_UpdatedAfterCpCommitted", List.of(101, "R1_AfterCP", 555));
            expected.put("Row_102_RestoredToCpValue", List.of(102, "R2_AtCP", 200));

            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("CrashHaltExitCode", exitCode);
            actual.put("CCMRecovered", invokeCcmIsRecovered(ccmRecovered));
            actual.put("Row_101_UpdatedAfterCpCommitted", rows.get(101));
            actual.put("Row_102_RestoredToCpValue", rows.get(102));

            boolean passed = Objects.equals(expected, actual);
            reports.add(new ScenarioReport(
                "Skenario 3: Crash Nyata Dengan Checkpoint (Redo & Undo Pasca-Checkpoint)",
                expected.toString(),
                actual.toString(),
                passed
            ));
            return passed;
        } finally {
            frmRecovered.close();
        }
    }

    /**
     * Skenario 4: Point-in-Time Recovery (PITR) di Antara Dua Commit
     *
     * Urutan Kejadian:
     * - t0:
     *   T0 BEGIN
     *   T0 INSERT R0 (100, BaseSnapshot, 10)
     *   T0 COMMIT
     *   Snapshot awal dibuat
     * - t1:
     *   T1 BEGIN
     *   T1 INSERT R1 (101, T1_Valid, 100)
     *   T1 COMMIT
     * - t2:
     *   T2 BEGIN
     *   T2 INSERT R3 (103, T2_UncommittedAtTarget, 300) -> Belum commit saat targetTime
     * - t3:
     *   Batas waktu targetTime diambil
     * - t4:
     *   T3 BEGIN
     *   T3 UPDATE R1 (101, T3_Future, 999)
     *   T3 COMMIT -> Commit setelah targetTime
     * - t5:
     *   T2 COMMIT -> Commit setelah targetTime dan BufferPool di-flush ke disk
     * - t6:
     *   Eksekusi recoverToPointInTime(targetTime) -> Snapshot awal di-restore, hanya transaksi
     *   yang commit sebelum targetTime (T0 dan T1) yang di-Redo, dan wal.log dipangkas
     * - t7:
     *   Eksekusi recoverOnStartup() untuk memverifikasi log setelah targetTime sudah terpotong
     *
     * Kondisi Akhir yang Diharapkan (Expected):
     * - Data R0 (100, BaseSnapshot, 10) ada di tabel (dari snapshot awal)
     * - Data R1 (101, T1_Valid, 100) ada di tabel (nilai saat targetTime, perubahan T3 tidak disertakan)
     * - Data R3 (103, T2_UncommittedAtTarget) tidak ada (null) karena T2 baru commit setelah targetTime
     */
    public boolean runPitrScenario() throws InterruptedException {
        Path scenarioDir = testDir.resolve("scenario_4_pitr");
        Path snapshotDir = scenarioDir.resolve("snapshot");
        cleanDirectory(scenarioDir);

        Object smInstance = createInitializedStorage(scenarioDir);
        IDataAccess smData = (IDataAccess) smInstance;
        IDurableStorage smDurable = (IDurableStorage) smInstance;

        FailureRecoveryFacade frm = new FailureRecoveryFacade(scenarioDir, snapshotDir, smData, smDurable);
        attachRecoveryManagerToStorage(smInstance, frm);
        IConcurrencyControl activeCcm = createRealConcurrencyControl(frm);
        invokeCcmInitAfterRecovery(activeCcm);

        try {
            // t0: T0 BEGIN, T0 INSERT R0 (100, BaseSnapshot, 10), T0 COMMIT, dan buat snapshot awal
            int t0 = activeCcm.beginTransaction();
            execInsert(frm, smData, activeCcm, t0, List.of(100, "BaseSnapshot", 10));
            activeCcm.commitTransaction(t0);
            frm.getCheckpointManager().createSnapshot();

            // t1: T1 BEGIN, T1 INSERT R1 (101, T1_Valid, 100), dan T1 COMMIT sebelum targetTime
            int t1 = activeCcm.beginTransaction();
            RowId r1 = execInsert(frm, smData, activeCcm, t1, List.of(101, "T1_Valid", 100));
            activeCcm.commitTransaction(t1);

            // t2: T2 BEGIN dan T2 INSERT R3 (103, T2_UncommittedAtTarget, 300) sebelum targetTime tanpa commit
            int t2 = activeCcm.beginTransaction();
            execInsert(frm, smData, activeCcm, t2, List.of(103, "T2_UncommittedAtTarget", 300));

            // t3: Catat waktu targetTime
            Thread.sleep(25);
            Instant targetTime = Instant.now();
            Thread.sleep(25);

            // t4: T3 BEGIN, T3 UPDATE R1 (101, T3_Future, 999), dan T3 COMMIT setelah targetTime
            int t3 = activeCcm.beginTransaction();
            execUpdate(frm, smData, activeCcm, t3, r1, List.of(101, "T1_Valid", 100), List.of(101, "T3_Future", 999));
            activeCcm.commitTransaction(t3);

            // t5: T2 COMMIT setelah targetTime dan flush buffer ke disk
            activeCcm.commitTransaction(t2);
            smDurable.flushBuffer();

            // t6: Pulihkan basis data ke targetTime
            RecoveryResult pitrRes = frm.recoverToPointInTime(targetTime);

            // t7: Jalankan recovery startup untuk verifikasi pemangkasan log
            RecoveryResult restartRes = frm.recoverOnStartup();

            Map<Integer, List<Object>> rows = readAllRowsByNim(smData);

            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("PitrSuccess", true);
            expected.put("MaxTxIdPreserved", 4);
            expected.put("Row_100_FromSnapshot", List.of(100, "BaseSnapshot", 10));
            expected.put("Row_101_AtTargetTime", List.of(101, "T1_Valid", 100));
            expected.put("Row_103_Excluded", null);

            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("PitrSuccess", pitrRes.isSuccess() && restartRes.isSuccess());
            actual.put("MaxTxIdPreserved", pitrRes.getMaxTxId());
            actual.put("Row_100_FromSnapshot", rows.get(100));
            actual.put("Row_101_AtTargetTime", rows.get(101));
            actual.put("Row_103_Excluded", rows.get(103));

            boolean passed = Objects.equals(expected, actual);
            reports.add(new ScenarioReport(
                "Skenario 4: Point-in-Time Recovery (PITR) di Antara Dua Commit",
                expected.toString(),
                actual.toString(),
                passed
            ));
            return passed;
        } finally {
            frm.close();
        }
    }

    public void printTestReport() {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("                         RINGKASAN HASIL PENGUJIAN FRM                          ");
        System.out.println("--------------------------------------------------------------------------------");
        int passedCount = 0;
        for (ScenarioReport report : reports) {
            if (report.passed) passedCount++;
            System.out.println("Skenario : " + report.name);
            System.out.println("Expected : " + report.expected);
            System.out.println("Actual   : " + report.actual);
            System.out.println("Status   : " + (report.passed ? "[PASS]" : "[FAIL]"));
            System.out.println("--------------------------------------------------------------------------------");
        }
        System.out.printf("TOTAL: %d/%d Skenario Lulus%n", passedCount, reports.size());
        System.out.println("================================================================================");
    }

    // Worker child process untuk simulasi crash

    private static void executeCrashWorkerWithoutCheckpoint(Path dir) {
        Object smInstance = createInitializedStorage(dir);
        IDataAccess smData = (IDataAccess) smInstance;
        IDurableStorage smDurable = (IDurableStorage) smInstance;

        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), smData, smDurable);
        attachRecoveryManagerToStorage(smInstance, frm);
        IConcurrencyControl ccm = createRealConcurrencyControl(frm);
        invokeCcmInitAfterRecovery(ccm);

        // t0: T2 BEGIN, T2 INSERT R2 (102, T2_Temp, 50), lalu T2 ABORT sebelum crash
        int t2 = ccm.beginTransaction();
        execInsert(frm, smData, ccm, t2, List.of(102, "T2_Temp", 50));
        ccm.abortTransaction(t2, "Simulasi abort sebelum crash");

        // t1: T3 BEGIN, T3 INSERT R3 (103, T3_Dirty1, 300), T3 UPDATE R3 (103, T3_Dirty2, 999), lalu flush ke disk tanpa commit
        int t3 = ccm.beginTransaction();
        RowId r3 = execInsert(frm, smData, ccm, t3, List.of(103, "T3_Dirty1", 300));
        RecoveryResult uRes = execUpdate(
            frm, smData, ccm, t3, r3, List.of(103, "T3_Dirty1", 300), List.of(103, "T3_Dirty2", 999)
        );
        frm.flushLogUpTo(uRes.getLsn());
        smDurable.flushBuffer();

        // t2: T1 BEGIN, T1 INSERT R1 (101, T1_Init, 100), T1 UPDATE R1 (101, T1_Final, 200), dan T1 COMMIT di RAM
        int t1 = ccm.beginTransaction();
        RowId r1 = execInsert(frm, smData, ccm, t1, List.of(101, "T1_Init", 100));
        execUpdate(frm, smData, ccm, t1, r1, List.of(101, "T1_Init", 100), List.of(101, "T1_Final", 200));
        ccm.commitTransaction(t1);

        // t3: Hentikan proses secara paksa (CRASH)
        Runtime.getRuntime().halt(99);
    }

    private static void executeCrashWorkerWithCheckpoint(Path dir) {
        Object smInstance = createInitializedStorage(dir);
        IDataAccess smData = (IDataAccess) smInstance;
        IDurableStorage smDurable = (IDurableStorage) smInstance;

        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), smData, smDurable);
        attachRecoveryManagerToStorage(smInstance, frm);
        IConcurrencyControl ccm = createRealConcurrencyControl(frm);
        invokeCcmInitAfterRecovery(ccm);

        // t0: T1 BEGIN, T1 INSERT R1 (101, R1_AtCP, 100), T1 INSERT R2 (102, R2_AtCP, 200), dan T1 COMMIT sebelum checkpoint
        int t1 = ccm.beginTransaction();
        RowId r1 = execInsert(frm, smData, ccm, t1, List.of(101, "R1_AtCP", 100));
        RowId r2 = execInsert(frm, smData, ccm, t1, List.of(102, "R2_AtCP", 200));
        ccm.commitTransaction(t1);

        // t1: Jalankan quiescent checkpoint
        invokeCcmQuiescentCheckpoint(ccm, frm);

        // t2: T3 BEGIN, T3 UPDATE R2 (102, R2_Dirty, 888), T3 DELETE R2 (102, R2_Dirty, 888), lalu flush ke disk tanpa commit
        int t3 = ccm.beginTransaction();
        execUpdate(frm, smData, ccm, t3, r2, List.of(102, "R2_AtCP", 200), List.of(102, "R2_Dirty", 888));
        RecoveryResult delRes = execDelete(frm, smData, ccm, t3, r2, List.of(102, "R2_Dirty", 888));
        frm.flushLogUpTo(delRes.getLsn());
        smDurable.flushBuffer();

        // t3: T2 BEGIN, T2 UPDATE R1 (101, R1_AfterCP, 555), dan T2 COMMIT di RAM tanpa flush ke disk
        int t2 = ccm.beginTransaction();
        execUpdate(frm, smData, ccm, t2, r1, List.of(101, "R1_AtCP", 100), List.of(101, "R1_AfterCP", 555));
        ccm.commitTransaction(t2);

        // t4: Hentikan proses secara paksa (CRASH)
        Runtime.getRuntime().halt(99);
    }

    private int spawnCrashWorker(String modeFlag, Path scenarioDir) {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            String classpath = buildExtendedClasspath();
            ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                FRMTestDriver.class.getName(),
                modeFlag,
                scenarioDir.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Baca habis output stream child process
                }
            }
            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Gagal menjalankan child process simulasi crash", e);
        }
    }

    // Helper operasi transaksi

    private static Object createInitializedStorage(Path dir) {
        Object smInstance = createRealStorageManager(dir);
        ISchemaCatalog catalog = (ISchemaCatalog) smInstance;
        if (catalog.getTableSchema(TABLE) == null) {
            TableSchema schema = new TableSchema(
                TABLE,
                List.of(
                    new ColumnDefinition("nim", DataType.INTEGER, 0, false),
                    new ColumnDefinition("nama", DataType.VARCHAR, 64, false),
                    new ColumnDefinition("nilai", DataType.INTEGER, 0, false)
                ),
                List.of("nim"),
                List.of()
            );
            catalog.updateTableSchema(schema);
        }
        return smInstance;
    }

    private static RowId execInsert(
        FailureRecoveryFacade frm,
        IDataAccess sm,
        IConcurrencyControl ccm,
        int txId,
        List<Object> afterVal
    ) {
        // Reservasi RowId dari StorageManager
        RowId rid = sm.reserveRowId(TABLE, afterVal);

        // Minta lock penulisan ke CCM
        long lsnBefore = frm.getLogManager().getCurrentLsn();
        AccessStatus status = ccm.requestWrite(txId, new DataItem(TABLE, rid), null, afterVal);
        if (status != AccessStatus.GRANTED) {
            sm.releaseRowId(rid);
            throw new IllegalStateException("CCM Lock denied (" + status + ") for txId=" + txId + " on " + rid);
        }

        // Catat log INSERT jika belum dicatat oleh CCM
        if (frm.getLogManager().getCurrentLsn() == lsnBefore) {
            frm.writeDataLog(txId, LogType.INSERT, TABLE, rid, null, new RecordImage(afterVal));
        }

        // Tulis data ke StorageManager
        sm.insertAt(rid, afterVal);
        return rid;
    }

    private static RecoveryResult execUpdate(
        FailureRecoveryFacade frm,
        IDataAccess sm,
        IConcurrencyControl ccm,
        int txId,
        RowId rid,
        List<Object> beforeVal,
        List<Object> afterVal
    ) {
        long lsnBefore = frm.getLogManager().getCurrentLsn();
        AccessStatus status = ccm.requestWrite(txId, new DataItem(TABLE, rid), beforeVal, afterVal);
        if (status != AccessStatus.GRANTED) {
            throw new IllegalStateException("CCM Lock denied (" + status + ") for txId=" + txId + " on " + rid);
        }

        RecoveryResult res;
        if (frm.getLogManager().getCurrentLsn() == lsnBefore) {
            res = frm.writeDataLog(
                txId,
                LogType.UPDATE,
                TABLE,
                rid,
                new RecordImage(beforeVal),
                new RecordImage(afterVal)
            );
        } else {
            res = new RecoveryResult(
                true,
                frm.getLogManager().getCurrentLsn() - 1,
                frm.getLogManager().getMaxTxId(),
                "Logged by CCM"
            );
        }

        sm.updateByRowId(rid, afterVal);
        return res;
    }

    private static RecoveryResult execDelete(
        FailureRecoveryFacade frm,
        IDataAccess sm,
        IConcurrencyControl ccm,
        int txId,
        RowId rid,
        List<Object> beforeVal
    ) {
        long lsnBefore = frm.getLogManager().getCurrentLsn();
        AccessStatus status = ccm.requestWrite(txId, new DataItem(TABLE, rid), beforeVal, null);
        if (status != AccessStatus.GRANTED) {
            throw new IllegalStateException("CCM Lock denied (" + status + ") for txId=" + txId + " on " + rid);
        }

        RecoveryResult res;
        if (frm.getLogManager().getCurrentLsn() == lsnBefore) {
            res = frm.writeDataLog(txId, LogType.DELETE, TABLE, rid, new RecordImage(beforeVal), null);
        } else {
            res = new RecoveryResult(
                true,
                frm.getLogManager().getCurrentLsn() - 1,
                frm.getLogManager().getMaxTxId(),
                "Logged by CCM"
            );
        }

        sm.deleteByRowId(rid);
        return res;
    }

    private static boolean isConflictingAccessBlocked(IConcurrencyControl ccm, int txId, RowId lockedRowId) {
        CompletableFuture<AccessStatus> future = CompletableFuture.supplyAsync(
            () -> ccm.requestRead(txId, new DataItem(TABLE, lockedRowId))
        );
        try {
            AccessStatus status = future.get(150, TimeUnit.MILLISECONDS);
            return status != AccessStatus.GRANTED;
        } catch (TimeoutException e) {
            // Thread menunggu lock dilepas sehingga akses terbukti ditahan CCM
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static Map<Integer, List<Object>> readAllRowsByNim(IDataAccess sm) {
        Map<Integer, List<Object>> map = new LinkedHashMap<>();
        Rows rows = sm.readBlock(new DataRetrieval(TABLE, List.of(), List.of()));
        if (rows != null && rows.rows() != null) {
            for (Row row : rows.rows()) {
                if (row != null && row.values() != null && !row.values().isEmpty()) {
                    Integer nim = (Integer) row.values().get(0);
                    map.put(nim, row.values());
                }
            }
        }
        return map;
    }

    // Inisialisasi kelas StorageManager dan ConcurrencyControlManager via reflection

    private static synchronized ClassLoader getSiblingModuleClassLoader() {
        if (siblingClassLoader != null) {
            return siblingClassLoader;
        }
        try {
            List<URL> urls = new ArrayList<>();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            String[] candidateDirs = {
                "mdbms-storage/target/classes",
                "../mdbms-storage/target/classes",
                "mdbms-concurrency/target/classes",
                "../mdbms-concurrency/target/classes"
            };
            for (String candidate : candidateDirs) {
                Path p = cwd.resolve(candidate).normalize();
                if (Files.exists(p)) {
                    urls.add(p.toUri().toURL());
                }
            }
            siblingClassLoader = new URLClassLoader(urls.toArray(new URL[0]), FRMTestDriver.class.getClassLoader());
            return siblingClassLoader;
        } catch (Exception e) {
            throw new RuntimeException("Gagal menyiapkan ClassLoader modul sibling", e);
        }
    }

    private static String buildExtendedClasspath() {
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String[] candidateDirs = {
            "mdbms-storage/target/classes",
            "../mdbms-storage/target/classes",
            "mdbms-concurrency/target/classes",
            "../mdbms-concurrency/target/classes"
        };
        for (String candidate : candidateDirs) {
            Path p = cwd.resolve(candidate).normalize();
            if (Files.exists(p)) {
                cp.append(File.pathSeparator).append(p.toAbsolutePath());
            }
        }
        return cp.toString();
    }

    private static Object createRealStorageManager(Path dataDir) {
        try {
            Class<?> smClass = Class.forName("mdbms.storage.StorageManager", true, getSiblingModuleClassLoader());
            Constructor<?> ctor = smClass.getConstructor(String.class, int.class, int.class);
            return ctor.newInstance(dataDir.toString(), BUFFER_CAPACITY, INDEX_MAX_DEGREE);
        } catch (Exception e) {
            throw new RuntimeException(
                "Gagal memuat mdbms.storage.StorageManager. Pastikan modul mdbms-storage sudah di-compile (mvn test-compile).",
                e
            );
        }
    }

    private static void attachRecoveryManagerToStorage(Object smInstance, IFailureRecoveryManager frm) {
        try {
            Method m = smInstance.getClass().getMethod("attachRecoveryManager", IFailureRecoveryManager.class);
            m.invoke(smInstance, frm);
        } catch (Exception ignored) {
        }
    }

    private static IConcurrencyControl createRealConcurrencyControl(IFailureRecoveryManager frm) {
        try {
            ClassLoader cl = getSiblingModuleClassLoader();
            Class<?> protocolInterface = Class.forName("mdbms.concurrency.ConcurrencyProtocol", true, cl);
            Class<?> lockProtocolClass = Class.forName("mdbms.concurrency.LockBasedProtocol", true, cl);
            Object lockProtocol = lockProtocolClass.getDeclaredConstructor().newInstance();

            Class<?> ccmClass = Class.forName("mdbms.concurrency.ConcurrencyControlManager", true, cl);
            Constructor<?> ctor = ccmClass.getConstructor(protocolInterface, IFailureRecoveryManager.class);
            return (IConcurrencyControl) ctor.newInstance(lockProtocol, frm);
        } catch (Exception e) {
            throw new RuntimeException(
                "Gagal memuat mdbms.concurrency.ConcurrencyControlManager. Pastikan modul mdbms-concurrency sudah di-compile (mvn test-compile).",
                e
            );
        }
    }

    private static void invokeCcmInitAfterRecovery(IConcurrencyControl ccm) {
        try {
            Method m = ccm.getClass().getMethod("initAfterRecovery");
            m.invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal menjalankan ccm.initAfterRecovery()", e);
        }
    }

    private static boolean invokeCcmIsRecovered(IConcurrencyControl ccm) {
        try {
            Method m = ccm.getClass().getMethod("isRecovered");
            return (Boolean) m.invoke(ccm);
        } catch (Exception e) {
            return false;
        }
    }

    private static void invokeCcmQuiescentCheckpoint(IConcurrencyControl ccm, IFailureRecoveryManager frm) {
        try {
            Method pause = ccm.getClass().getMethod("pauseTransactions");
            Method await = ccm.getClass().getMethod("awaitNoActiveTransactions");
            Method resume = ccm.getClass().getMethod("resumeTransactions");
            pause.invoke(ccm);
            await.invoke(ccm);
            frm.createCheckpoint();
            resume.invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal mengeksekusi quiescent checkpoint via CCM", e);
        }
    }

    private static void cleanDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record ScenarioReport(String name, String expected, String actual, boolean passed) {}
}