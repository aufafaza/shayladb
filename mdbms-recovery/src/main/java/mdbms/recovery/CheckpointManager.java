package mdbms.recovery;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import mdbms.api.IDurableStorage;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecoveryResult;

public class CheckpointManager {
    public static final String SNAPSHOT_META_FILE = "snapshot.meta";
    private static final String META_VERSION = "1";

    private final LogManager logManager;
    private final IDurableStorage storageManager;
    private final RecordApplier recordApplier;
    private final String dataDirectory;
    private final String snapshotPath;

    public CheckpointManager(
            LogManager logManager,
            IDurableStorage storageManager,
            RecordApplier recordApplier,
            String dataDirectory,
            String snapshotPath) {
        this.logManager = logManager;
        this.storageManager = storageManager;
        this.recordApplier = recordApplier;
        this.dataDirectory = dataDirectory;
        this.snapshotPath = snapshotPath;
    }

    public RecoveryResult executeCheckpoint() {
        return executeCheckpoint(detectActiveTransactions());
    }

    public RecoveryResult executeCheckpoint(List<Integer> activeTxIds) {
        if (storageManager == null) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "CHECKPOINT: StorageManager tidak tersedia");
        }
        try {
            long targetLsn = logManager.getCurrentLsn() - 1;
            if (targetLsn > 0 && !logManager.flushUpTo(targetLsn)) {
                return new RecoveryResult(false, -1L, logManager.getMaxTxId(),
                        "CHECKPOINT: Gagal flush WAL sebelum flush buffer");
            }
            storageManager.flushBuffer();
            CheckpointLogRecord cp = logManager.appendCheckpointLog(activeTxIds);
            return new RecoveryResult(
                    true,
                    cp.getLsn(),
                    logManager.getMaxTxId(),
                    "Checkpoint created successfully"
            );
        } catch (RuntimeException e) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "CHECKPOINT: " + e.getMessage());
        }
    }

    public RecoveryResult recoverToPointInTime(Instant targetTimestamp) {
        if (storageManager == null || recordApplier == null || recordApplier.getDataAccess() == null) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "PITR: Layanan storage tidak tersedia");
        }
        if (targetTimestamp == null) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "PITR: Target timestamp tidak boleh null");
        }

        try {
            // 1. Validasi manifest snapshot & jangkauan target SEBELUM menyentuh storage aktif
            SnapshotMetadata meta = readSnapshotMetadata();
            if (targetTimestamp.isBefore(meta.baselineTimestamp())) {
                return new RecoveryResult(
                        false,
                        -1L,
                        logManager.getMaxTxId(),
                        "PITR ditolak: target timestamp (" + targetTimestamp
                                + ") berada sebelum snapshot baseline (" + meta.baselineTimestamp() + ")"
                );
            }

            List<LogRecord> allLogs = logManager.readLogs(null);
            validateWalHistoryCoverage(allLogs, meta.baselineLsn());

            // 2. Hitung rencana replay hanya untuk catatan sesudah baselineLsn
            Set<Integer> committedTxs = new HashSet<>();
            List<DataChangeLogRecord> recordsToRedo = new ArrayList<>();
            long lastProcessedLsn = meta.baselineLsn();

            for (LogRecord record : allLogs) {
                if (record.getLsn() <= meta.baselineLsn()) {
                    continue;
                }
                if (record.getTimestamp().isAfter(targetTimestamp)) {
                    break;
                }
                if (record.getType() == LogType.COMMIT) {
                    committedTxs.add(record.getTxId());
                }
                if (record instanceof DataChangeLogRecord dataRecord) {
                    recordsToRedo.add(dataRecord);
                }
                lastProcessedLsn = record.getLsn();
            }

            // 3. Baru pulihkan snapshot setelah seluruh validasi lolos
            restoreSnapshot();

            for (DataChangeLogRecord record : recordsToRedo) {
                if (committedTxs.contains(record.getTxId()) && !recordApplier.redo(record)) {
                    return new RecoveryResult(false, record.getLsn(), logManager.getMaxTxId(),
                            "PITR: Gagal redo LSN=" + record.getLsn());
                }
            }

            if (lastProcessedLsn > 0 && !logManager.flushUpTo(lastProcessedLsn)) {
                return new RecoveryResult(false, -1L, logManager.getMaxTxId(),
                        "PITR: Gagal flush WAL sebelum persist hasil recovery");
            }
            storageManager.rebuildIndexesAndReloadCatalog();
            storageManager.flushBuffer();
            truncateLogAfter(lastProcessedLsn);

            return new RecoveryResult(true, lastProcessedLsn, logManager.getMaxTxId(),
                    "PITR completed up to: " + targetTimestamp);
        } catch (RuntimeException e) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "PITR gagal: " + e.getMessage());
        }
    }

    public RecoveryResult recoverToTransaction(int targetTxId) {
        if (storageManager == null || recordApplier == null || recordApplier.getDataAccess() == null) {
            return new RecoveryResult(
                    false,
                    -1L,
                    logManager.getMaxTxId(),
                    "Recovery to transaction: Layanan storage tidak tersedia"
            );
        }
        if (targetTxId <= 0) {
            return new RecoveryResult(false, -1L, logManager.getMaxTxId(),
                    "Recovery to transaction: targetTxId tidak valid (" + targetTxId + ")");
        }

        try {
            // 1. Validasi manifest snapshot, kontinuitas WAL, dan batas baseline SEBELUM menyentuh storage aktif
            SnapshotMetadata meta = readSnapshotMetadata();
            List<LogRecord> allLogs = logManager.readLogs(null);
            validateWalHistoryCoverage(allLogs, meta.baselineLsn());

            long targetCommitLsn = -1L;
            for (LogRecord record : allLogs) {
                if (record.getTxId() == targetTxId && record.getType() == LogType.COMMIT) {
                    targetCommitLsn = record.getLsn();
                    break;
                }
            }

            if (targetCommitLsn == -1L) {
                return new RecoveryResult(false, -1L, logManager.getMaxTxId(), "Target transaction did not commit");
            }
            if (targetCommitLsn < meta.baselineLsn()) {
                return new RecoveryResult(
                        false,
                        -1L,
                        logManager.getMaxTxId(),
                        "Recovery to transaction ditolak: COMMIT txId=" + targetTxId
                                + " (LSN=" + targetCommitLsn + ") berada sebelum snapshot baseline (LSN="
                                + meta.baselineLsn() + ")"
                );
            }

            // 2. Hitung rencana replay hanya untuk catatan sesudah baselineLsn hingga targetCommitLsn
            Set<Integer> committedTxs = new HashSet<>();
            List<DataChangeLogRecord> recordsToRedo = new ArrayList<>();
            long lastProcessedLsn = meta.baselineLsn();

            for (LogRecord record : allLogs) {
                if (record.getLsn() <= meta.baselineLsn()) {
                    continue;
                }
                if (record.getLsn() > targetCommitLsn) {
                    break;
                }
                if (record.getType() == LogType.COMMIT) {
                    committedTxs.add(record.getTxId());
                }
                if (record instanceof DataChangeLogRecord dataRecord) {
                    recordsToRedo.add(dataRecord);
                }
                lastProcessedLsn = record.getLsn();
            }

            // 3. Baru pulihkan snapshot setelah seluruh validasi lolos
            restoreSnapshot();

            for (DataChangeLogRecord record : recordsToRedo) {
                if (committedTxs.contains(record.getTxId()) && !recordApplier.redo(record)) {
                    return new RecoveryResult(
                            false,
                            record.getLsn(),
                            logManager.getMaxTxId(),
                            "Recovery to transaction: Gagal redo LSN=" + record.getLsn()
                    );
                }
            }

            if (lastProcessedLsn > 0 && !logManager.flushUpTo(lastProcessedLsn)) {
                return new RecoveryResult(false, -1L, logManager.getMaxTxId(),
                        "Recovery to transaction: Gagal flush WAL sebelum persist hasil recovery");
            }
            storageManager.rebuildIndexesAndReloadCatalog();
            storageManager.flushBuffer();
            truncateLogAfter(lastProcessedLsn);

            return new RecoveryResult(
                    true,
                    lastProcessedLsn,
                    logManager.getMaxTxId(),
                    "Recovery completed up to transaction " + targetTxId
            );
        } catch (RuntimeException e) {
            return new RecoveryResult(
                    false,
                    -1L,
                    logManager.getMaxTxId(),
                    "Recovery to transaction gagal: " + e.getMessage()
            );
        }
    }

    public CheckpointLogRecord findLastCompletedCheckpoint() {
        List<LogRecord> allLogs = logManager.readLogs(null);
        for (int i = allLogs.size() - 1; i >= 0; i--) {
            LogRecord record = allLogs.get(i);
            if (record.getType() == LogType.CHECKPOINT && record instanceof CheckpointLogRecord cp) {
                return cp;
            }
        }
        return null;
    }

    /**
     * Membuat satu baseline snapshot yang konsisten untuk FR-05, membersihkan berkas snapshot basi,
     * dan mencatat manifest snapshot.meta (baselineLsn & baselineTimestamp) secara atomik.
     */
    public void createSnapshot() {
        if (dataDirectory == null || snapshotPath == null) {
            return;
        }
        List<Integer> activeTxs = detectActiveTransactions();
        if (!activeTxs.isEmpty()) {
            throw new IllegalStateException(
                    "Snapshot baseline tidak dapat dibuat saat masih ada transaksi aktif di WAL: " + activeTxs);
        }

        List<LogRecord> allLogs = logManager.readLogs(null);
        long baselineLsn = 0L;
        Instant baselineTimestamp = Instant.now();
        if (!allLogs.isEmpty()) {
            LogRecord last = allLogs.get(allLogs.size() - 1);
            baselineLsn = last.getLsn();
            baselineTimestamp = last.getTimestamp();
            if (baselineLsn > 0 && !logManager.flushUpTo(baselineLsn)) {
                throw new RuntimeException("Gagal flush WAL sebelum membuat snapshot pada LSN=" + baselineLsn);
            }
        }

        if (storageManager != null) {
            storageManager.flushBuffer();
        }

        Path srcDir = Path.of(dataDirectory).toAbsolutePath().normalize();
        Path snapDir = Path.of(snapshotPath).toAbsolutePath().normalize();
        Path walPath = logManager.getWalFileHandler().getLogFilePath().toAbsolutePath().normalize();
        Path metaFile = snapDir.resolve(SNAPSHOT_META_FILE);

        try {
            if (!Files.exists(srcDir)) {
                return;
            }
            Files.createDirectories(snapDir);
            // Hapus manifest lama lebih dulu agar snapshot setengah jalan tidak pernah terbaca valid
            Files.deleteIfExists(metaFile);

            Set<Path> copiedRelativePaths = new HashSet<>();
            try (var stream = Files.walk(srcDir)) {
                stream.filter(Files::isRegularFile).forEach(srcFile -> {
                    Path absSrc = srcFile.toAbsolutePath().normalize();
                    if (shouldExcludeFromSnapshot(absSrc, walPath, snapDir)) {
                        return;
                    }
                    try {
                        Path rel = srcDir.relativize(absSrc);
                        Path target = snapDir.resolve(rel);
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.copy(absSrc, target, StandardCopyOption.REPLACE_EXISTING);
                        copiedRelativePaths.add(rel);
                    } catch (IOException e) {
                        throw new RuntimeException("Gagal menyalin berkas ke snapshot: " + srcFile, e);
                    }
                });
            }

            // Hapus berkas basi yang tersisa dari snapshot sebelumnya
            try (var existing = Files.walk(snapDir)) {
                existing.filter(Files::isRegularFile).forEach(snapFile -> {
                    Path absSnap = snapFile.toAbsolutePath().normalize();
                    if (absSnap.equals(metaFile)) {
                        return;
                    }
                    Path rel = snapDir.relativize(absSnap);
                    if (!copiedRelativePaths.contains(rel)) {
                        try {
                            Files.deleteIfExists(absSnap);
                        } catch (IOException e) {
                            throw new RuntimeException("Gagal menghapus berkas snapshot basi: " + snapFile, e);
                        }
                    }
                });
            }

            // Tulis snapshot.meta secara atomik sebagai penanda selesai
            writeSnapshotMetadata(metaFile, new SnapshotMetadata(baselineLsn, baselineTimestamp));
        } catch (IOException e) {
            throw new RuntimeException("Gagal membuat snapshot", e);
        }
    }

    public SnapshotMetadata getSnapshotMetadata() {
        return readSnapshotMetadata();
    }

    private SnapshotMetadata readSnapshotMetadata() {
        if (snapshotPath == null || dataDirectory == null) {
            throw new IllegalStateException("Path snapshot atau data directory tidak dikonfigurasi");
        }
        Path snapDir = Path.of(snapshotPath).toAbsolutePath().normalize();
        Path metaFile = snapDir.resolve(SNAPSHOT_META_FILE);
        if (!Files.exists(snapDir) || !Files.exists(metaFile)) {
            throw new IllegalStateException("Snapshot baseline atau manifest tidak ditemukan: " + metaFile);
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Gagal membaca manifest snapshot: " + metaFile, e);
        }

        String version = props.getProperty("version");
        String lsnRaw = props.getProperty("baselineLsn");
        String tsRaw = props.getProperty("baselineTimestamp");
        if (!META_VERSION.equals(version) || lsnRaw == null || tsRaw == null) {
            throw new IllegalStateException("Manifest snapshot tidak lengkap atau korup: " + metaFile);
        }
        try {
            long baselineLsn = Long.parseLong(lsnRaw.trim());
            if (baselineLsn < 0) {
                throw new IllegalStateException("baselineLsn negatif pada manifest snapshot");
            }
            Instant baselineTs = Instant.parse(tsRaw.trim());
            return new SnapshotMetadata(baselineLsn, baselineTs);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Format manifest snapshot tidak valid: " + metaFile, e);
        }
    }

    private void writeSnapshotMetadata(Path metaFile, SnapshotMetadata meta) throws IOException {
        Path tempMeta = metaFile.resolveSibling(SNAPSHOT_META_FILE + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tempMeta.toFile());
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write("version=" + META_VERSION + "\n");
                writer.write("baselineLsn=" + meta.baselineLsn() + "\n");
                writer.write("baselineTimestamp=" + meta.baselineTimestamp() + "\n");
                writer.flush();
                fos.getFD().sync();
            }
            try {
                Files.move(tempMeta, metaFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempMeta, metaFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tempMeta);
            } catch (IOException ignored) {
            }
        }
    }

    private void validateWalHistoryCoverage(List<LogRecord> allLogs, long baselineLsn) {
        if (baselineLsn == 0L) {
            if (!allLogs.isEmpty() && allLogs.get(0).getLsn() > 1L) {
                throw new IllegalStateException(
                        "Riwayat WAL tidak lengkap sejak baseline LSN=0 (LSN pertama di WAL="
                                + allLogs.get(0).getLsn() + ")");
            }
            return;
        }
        if (allLogs.isEmpty()) {
            throw new IllegalStateException(
                    "Riwayat WAL kosong sehingga kontinuitas dari baseline LSN=" + baselineLsn
                            + " tidak dapat diverifikasi");
        }
        boolean containsBaseline = false;
        for (LogRecord rec : allLogs) {
            if (rec.getLsn() == baselineLsn) {
                containsBaseline = true;
                break;
            }
        }
        long firstLsn = allLogs.get(0).getLsn();
        if (!containsBaseline && firstLsn != baselineLsn + 1L) {
            throw new IllegalStateException(
                    "Riwayat WAL terputus antara snapshot baseline LSN=" + baselineLsn
                            + " dan awal WAL LSN=" + firstLsn);
        }
    }

    private static boolean shouldExcludeFromSnapshot(Path absFile, Path walPath, Path snapDir) {
        if (absFile.equals(walPath) || absFile.startsWith(snapDir)) {
            return true;
        }
        String name = absFile.getFileName().toString();
        return name.endsWith(".rewrite.tmp") || name.endsWith(".tmp");
    }

    private List<Integer> detectActiveTransactions() {
        List<LogRecord> allLogs = logManager.readLogs(null);
        Set<Integer> active = new LinkedHashSet<>();
        for (LogRecord rec : allLogs) {
            int txId = rec.getTxId();
            if (txId <= 0) {
                continue;
            }
            switch (rec.getType()) {
                case BEGIN, INSERT, UPDATE, DELETE -> active.add(txId);
                case COMMIT, ABORT -> active.remove(txId);
                default -> {
                }
            }
        }
        return new ArrayList<>(active);
    }

    private void restoreSnapshot() {
        if (snapshotPath == null || dataDirectory == null) {
            throw new IllegalStateException("Path snapshot atau data directory tidak dikonfigurasi");
        }
        Path snapDir = Path.of(snapshotPath).toAbsolutePath().normalize();
        Path destDir = Path.of(dataDirectory).toAbsolutePath().normalize();
        Path walPath = logManager.getWalFileHandler().getLogFilePath().toAbsolutePath().normalize();
        Path metaFile = snapDir.resolve(SNAPSHOT_META_FILE);

        if (!Files.exists(snapDir)) {
            throw new IllegalStateException("Direktori snapshot tidak ditemukan: " + snapDir);
        }

        Set<String> invalidatedFiles = new HashSet<>();
        Set<Path> snapshotRelativePaths = new HashSet<>();
        try {
            Files.createDirectories(destDir);
            try (var snapStream = Files.walk(snapDir)) {
                snapStream.filter(Files::isRegularFile).forEach(srcFile -> {
                    Path absSrc = srcFile.toAbsolutePath().normalize();
                    if (absSrc.equals(metaFile) || absSrc.getFileName().toString().endsWith(".tmp")) {
                        return;
                    }
                    Path rel = snapDir.relativize(absSrc);
                    snapshotRelativePaths.add(rel);
                    invalidatedFiles.add(absSrc.getFileName().toString());
                    try {
                        Path target = destDir.resolve(rel);
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.copy(absSrc, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException("Gagal menyalin snapshot: " + srcFile, e);
                    }
                });
            }

            // Hapus file data di destDir yang dibuat sesudah snapshot agar tidak tertinggal
            try (var destStream = Files.walk(destDir)) {
                destStream.filter(Files::isRegularFile).forEach(destFile -> {
                    Path absDest = destFile.toAbsolutePath().normalize();
                    if (shouldExcludeFromSnapshot(absDest, walPath, snapDir)) {
                        return;
                    }
                    Path rel = destDir.relativize(absDest);
                    if (!snapshotRelativePaths.contains(rel)) {
                        invalidatedFiles.add(absDest.getFileName().toString());
                        try {
                            Files.deleteIfExists(absDest);
                        } catch (IOException e) {
                            throw new RuntimeException("Gagal menghapus berkas data pasca-snapshot: " + destFile, e);
                        }
                    }
                });
            }

            if (storageManager != null) {
                storageManager.invalidateAndReload(invalidatedFiles);
            }
        } catch (Exception e) {
            throw new RuntimeException("Gagal melakukan restore snapshot", e);
        }
    }

    private void truncateLogAfter(long lsn) {
        logManager.truncateLogsAfter(lsn);
    }

    public record SnapshotMetadata(long baselineLsn, Instant baselineTimestamp) {}
}