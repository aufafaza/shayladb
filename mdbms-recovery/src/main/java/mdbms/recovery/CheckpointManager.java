package mdbms.recovery;

import mdbms.api.IDurableStorage;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecoveryResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;

public class CheckpointManager {
    private final LogManager logManager;
    private final IDurableStorage storageManager;
    private final RecordApplier recordApplier;
    private final String dataDirectory;
    private final String snapshotPath;

    public CheckpointManager(LogManager logManager, IDurableStorage storageManager, RecordApplier recordApplier, String dataDirectory, String snapshotPath) {
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
        logManager.flushUpTo(logManager.getCurrentLsn() - 1);
        
        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        
        CheckpointLogRecord cp = logManager.appendCheckpointLog(activeTxIds);
        
        return new RecoveryResult(
            true, 
            cp.getLsn(), 
            logManager.getMaxTxId(), 
            "Checkpoint created successfully"
        );
    }

    public RecoveryResult recoverToPointInTime(Instant targetTimestamp) {
        restoreSnapshot(); 

        List<LogRecord> allLogs = logManager.readLogs(null);
        
        Set<Integer> committedTxs = new HashSet<>();
        List<DataChangeLogRecord> recordsToRedo = new ArrayList<>();
        long lastProcessedLsn = -1;

        for (LogRecord record : allLogs) {
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

        for (DataChangeLogRecord record : recordsToRedo) {
            if (committedTxs.contains(record.getTxId())) {
                recordApplier.redo(record);
            }
        }

        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        truncateLogAfter(lastProcessedLsn);

        return new RecoveryResult(
            true, 
            lastProcessedLsn, 
            logManager.getMaxTxId(), 
            "PITR completed up to: " + targetTimestamp
        );
    }

    public RecoveryResult recoverToTransaction(int targetTxId) {
        restoreSnapshot();

        List<LogRecord> allLogs = logManager.readLogs(null);
        Set<Integer> committedTxs = new HashSet<>();
        List<DataChangeLogRecord> recordsToRedo = new ArrayList<>();
        
        long targetCommitLsn = -1;

        for (LogRecord record : allLogs) {
            if (record.getTxId() == targetTxId && record.getType() == LogType.COMMIT) {
                targetCommitLsn = record.getLsn();
                break;
            }
        }
        
        if (targetCommitLsn == -1) {
            return new RecoveryResult(false, -1, logManager.getMaxTxId(), "Target transaction did not commit");
        }

        long lastProcessedLsn = -1;

        for (LogRecord record : allLogs) {
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

        for (DataChangeLogRecord record : recordsToRedo) {
            if (committedTxs.contains(record.getTxId())) {
                recordApplier.redo(record);
            }
        }

        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        truncateLogAfter(lastProcessedLsn);

        return new RecoveryResult(
            true, 
            lastProcessedLsn, 
            logManager.getMaxTxId(), 
            "Recovery completed up to transaction " + targetTxId
        );
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

    public void createSnapshot() {
        if (dataDirectory == null || snapshotPath == null) return;
        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        Path srcDir = Path.of(dataDirectory).toAbsolutePath().normalize();
        Path snapDir = Path.of(snapshotPath).toAbsolutePath().normalize();
        Path walPath = logManager.getWalFileHandler().getLogFilePath().toAbsolutePath().normalize();

        try {
            if (!Files.exists(srcDir)) return;
            Files.createDirectories(snapDir);
            try (var stream = Files.walk(srcDir)) {
                stream.filter(Files::isRegularFile).forEach(srcFile -> {
                    Path absSrc = srcFile.toAbsolutePath().normalize();
                    if (absSrc.equals(walPath) || absSrc.startsWith(snapDir)) return;
                    try {
                        Path target = snapDir.resolve(srcDir.relativize(absSrc));
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(absSrc, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException("Gagal membuat snapshot: " + srcFile, e);
                    }
                });
            }
        } catch (IOException e) {
            throw new RuntimeException("Gagal membuat snapshot", e);
        }
    }

    private List<Integer> detectActiveTransactions() {
        List<LogRecord> allLogs = logManager.readLogs(null);
        Set<Integer> active = new LinkedHashSet<>();
        for (LogRecord rec : allLogs) {
            int txId = rec.getTxId();
            if (txId <= 0) continue;
            switch (rec.getType()) {
                case BEGIN, INSERT, UPDATE, DELETE -> active.add(txId);
                case COMMIT, ABORT -> active.remove(txId);
                default -> {}
            }
        }
        return new ArrayList<>(active);
    }

    private void restoreSnapshot() {
        if (snapshotPath == null || dataDirectory == null) return;
        Path snapDir = Path.of(snapshotPath).toAbsolutePath().normalize();
        Path destDir = Path.of(dataDirectory).toAbsolutePath().normalize();
        if (!Files.exists(snapDir)) return;

        Set<String> restoredFiles = new HashSet<>();
        try {
            Files.createDirectories(destDir);
            try (var snapStream = Files.walk(snapDir)) {
                snapStream.filter(Files::isRegularFile).forEach(srcFile -> {
                    Path absSrc = srcFile.toAbsolutePath().normalize();
                    restoredFiles.add(absSrc.getFileName().toString());
                    try {
                        Path target = destDir.resolve(snapDir.relativize(absSrc));
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(absSrc, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException("Gagal menyalin snapshot: " + srcFile, e);
                    }
                });
            }

            if (storageManager != null) {
                Class<?> smClass = storageManager.getClass();
                Field bpField = smClass.getDeclaredField("bufferPool");
                bpField.setAccessible(true);
                Object bufferPool = bpField.get(storageManager);
                Method discardMethod = bufferPool.getClass().getDeclaredMethod("discardFile", String.class);
                discardMethod.setAccessible(true);
                for (String fileName : restoredFiles) {
                    discardMethod.invoke(bufferPool, fileName);
                }

                Field tablesField = smClass.getDeclaredField("tables");
                tablesField.setAccessible(true);
                if (tablesField.get(storageManager) instanceof Map<?, ?> tablesMap) {
                    for (Object tableName : new ArrayList<>(tablesMap.keySet())) {
                        discardMethod.invoke(bufferPool, tableName + ".tbl");
                    }
                    tablesMap.clear();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Gagal melakukan restore snapshot", e);
        }
    }

    private void truncateLogAfter(long lsn) {
        List<LogRecord> allLogs = logManager.readLogs(null);
        WalFileHandler handler = logManager.getWalFileHandler();
        handler.close();
        try {
            Files.deleteIfExists(handler.getLogFilePath());
        } catch (IOException e) {
            throw new RuntimeException("Gagal memangkas berkas WAL", e);
        }
        for (LogRecord rec : allLogs) {
            if (rec.getLsn() <= lsn) {
                handler.append(rec);
            }
        }
        if (lsn > 0) {
            handler.flushUpTo(lsn);
        }
    }
}