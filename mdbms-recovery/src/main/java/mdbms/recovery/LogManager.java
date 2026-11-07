package mdbms.recovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import mdbms.models.data.RowId;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.TransactionLogRecord;

/**
 * Mengelola pencatatan log transaksi, perubahan data, dan urutan LSN.
 */
public class LogManager {

    private final WalFileHandler walFileHandler;
    private final AtomicLong nextLsn = new AtomicLong(1L);
    private volatile int maxTxId = 0;

    public LogManager() {
        this(new WalFileHandler());
    }

    public LogManager(WalFileHandler walFileHandler) {
        this.walFileHandler = walFileHandler;
        recoverStateFromDisk();
    }

    /**
     * Membaca LSN dan ID transaksi tertinggi dari berkas log saat startup.
     */
    private void recoverStateFromDisk() {
        List<LogRecord> existingLogs = walFileHandler.readAll();
        long highestLsn = 0L;
        int highestTxId = 0;

        for (LogRecord record : existingLogs) {
            if (record.getLsn() > highestLsn) {
                highestLsn = record.getLsn();
            }
            if (record.getTxId() > highestTxId) {
                highestTxId = record.getTxId();
            }
        }

        this.nextLsn.set(highestLsn + 1);
        this.maxTxId = highestTxId;
    }

    /**
     * Mencatat peristiwa transaksi (BEGIN, COMMIT, ABORT).
     */
    public TransactionLogRecord appendTransactionLog(int txId, LogType eventType) {
        long lsn = nextLsn.getAndIncrement();
        Instant now = Instant.now();
        TransactionLogRecord record = new TransactionLogRecord(lsn, txId, eventType, now);

        boolean ok = walFileHandler.append(record);
        if (!ok) {
            throw new RuntimeException("Gagal menulis log transaksi untuk txId=" + txId);
        }

        updateMaxTxId(txId);

        if (eventType == LogType.COMMIT) {
            boolean flushed = walFileHandler.flushUpTo(lsn);
            if (!flushed) {
                throw new RuntimeException("Gagal flush log COMMIT untuk txId=" + txId);
            }
        }

        return record;
    }

    /**
     * Mencatat perubahan data (INSERT, UPDATE, DELETE).
     */
    public DataChangeLogRecord appendDataLog(
        int txId,
        LogType opType,
        String table,
        RowId rid,
        RecordImage beforeImg,
        RecordImage afterImg
    ) {
        long lsn = nextLsn.getAndIncrement();
        Instant now = Instant.now();
        DataChangeLogRecord record = new DataChangeLogRecord(lsn, txId, opType, now, table, rid, beforeImg, afterImg);

        boolean ok = walFileHandler.append(record);
        if (!ok) {
            throw new RuntimeException("Gagal menulis log perubahan data untuk txId=" + txId);
        }

        updateMaxTxId(txId);
        return record;
    }

    /**
     * Mencatat penanda checkpoint.
     */
    public CheckpointLogRecord appendCheckpointLog(List<Integer> activeTxIds) {
        long lsn = nextLsn.getAndIncrement();
        Instant now = Instant.now();
        CheckpointLogRecord record = new CheckpointLogRecord(lsn, now, activeTxIds);

        boolean ok = walFileHandler.append(record);
        if (!ok) {
            throw new RuntimeException("Gagal menulis log checkpoint");
        }

        walFileHandler.flushUpTo(lsn);
        return record;
    }

    /**
     * Memastikan log hingga LSN tertentu telah tersimpan di disk.
     */
    public boolean flushUpTo(long lsn) {
        return walFileHandler.flushUpTo(lsn);
    }

    /**
     * Membaca daftar catatan log, dengan opsi filter berdasarkan ID transaksi.
     */
    public List<LogRecord> readLogs(Integer filterTxId) {
        List<LogRecord> allLogs = walFileHandler.readAll();
        if (filterTxId == null) {
            return Collections.unmodifiableList(allLogs);
        }

        List<LogRecord> filtered = new ArrayList<>();
        for (LogRecord record : allLogs) {
            if (record.getTxId() == filterTxId) {
                filtered.add(record);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    /**
     * Menampilkan daftar log dalam format tabel teks.
     */
    public String formatLogTable(List<LogRecord> logs) {
        if (logs == null || logs.isEmpty()) {
            return "--- [Log Kosong] ---";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-6s | %-6s | %-10s | %-30s | %-12s | %-10s | %-20s | %-20s%n",
                "LSN", "TxID", "Type", "Timestamp", "Table", "RowId", "Before Image", "After Image"));
        sb.append("-".repeat(130)).append("\n");

        for (LogRecord r : logs) {
            if (r instanceof TransactionLogRecord txLog) {
                sb.append(String.format("%-6d | %-6d | %-10s | %-30s | %-12s | %-10s | %-20s | %-20s%n",
                        txLog.getLsn(), txLog.getTxId(), txLog.getType(), txLog.getTimestamp(), "-", "-", "-", "-"));
            } else if (r instanceof DataChangeLogRecord changeLog) {
                String rowIdStr = changeLog.getRowId() != null
                        ? changeLog.getRowId().blockNumber() + ":" + changeLog.getRowId().slot()
                        : "-";
                String beforeStr = changeLog.getBeforeImage() != null
                        ? changeLog.getBeforeImage().values().toString()
                        : "<ABSENT>";
                String afterStr = changeLog.getAfterImage() != null
                        ? changeLog.getAfterImage().values().toString()
                        : "<ABSENT>";
                sb.append(String.format("%-6d | %-6d | %-10s | %-30s | %-12s | %-10s | %-20s | %-20s%n",
                        changeLog.getLsn(), changeLog.getTxId(), changeLog.getType(), changeLog.getTimestamp(),
                        changeLog.getTable(), rowIdStr, beforeStr, afterStr));
            } else if (r instanceof CheckpointLogRecord cp) {
                sb.append(String.format("%-6d | %-6s | %-10s | %-30s | %-12s | %-10s | %-20s | %-20s%n",
                        cp.getLsn(), "-", cp.getType(), cp.getTimestamp(), "ACTIVE_TXS", cp.getActiveTxIds().toString(), "-", "-"));
            }
        }
        return sb.toString();
    }

    public synchronized void updateMaxTxId(int txId) {
        if (txId > this.maxTxId) {
            this.maxTxId = txId;
        }
    }

    public int getMaxTxId() {
        return maxTxId;
    }

    public long getCurrentLsn() {
        return nextLsn.get();
    }

    public WalFileHandler getWalFileHandler() {
        return walFileHandler;
    }
}
