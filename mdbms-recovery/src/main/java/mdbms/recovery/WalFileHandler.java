package mdbms.recovery;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mdbms.models.data.RowId;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.TransactionLogRecord;

/**
 * Menangani operasi I/O dan persistensi berkas WAL berbasis baris teks.
 */
public class WalFileHandler {

    public static final String DEFAULT_WAL_PATH = "wal.log";
    public static final String ABSENT_TOKEN = "<ABSENT>";
    private static final String FIELD_DELIMITER = "|";

    private final Path logFilePath;
    private final Object ioLock = new Object();
    private FileOutputStream fos;
    private FileChannel channel;
    private volatile long flushedLsn = 0L;
    private volatile long lastWrittenLsn = 0L;
    private record RawWalLine(String text, int endOffset, boolean hasNewline) {}

    public WalFileHandler() {
        this(Path.of(DEFAULT_WAL_PATH));
    }

    public WalFileHandler(String filePath) {
        this(Path.of(filePath));
    }

    public WalFileHandler(Path logFilePath) {
        this.logFilePath = logFilePath;
        initChannel();
    }

    private void initChannel() {
        synchronized (ioLock) {
            boolean interrupted = Thread.interrupted();
            try {
                if (logFilePath.getParent() != null) {
                    Files.createDirectories(logFilePath.getParent());
                }
                this.fos = new FileOutputStream(logFilePath.toFile(), true);
                this.channel = fos.getChannel();
            } catch (IOException e) {
                throw new RuntimeException("Gagal membuka berkas WAL: " + logFilePath, e);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Menambahkan satu catatan log ke akhir berkas WAL.
     */
    public boolean append(LogRecord record) {
        if (record == null) {
            return false;
        }

        String serializedLine = formatRecord(record) + "\n";
        byte[] bytes = serializedLine.getBytes(StandardCharsets.UTF_8);

        synchronized (ioLock) {
            boolean interrupted = Thread.interrupted();
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        if (channel == null || !channel.isOpen()) {
                            initChannel();
                        }
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) {
                            channel.write(buffer);
                        }
                        if (record.getLsn() > lastWrittenLsn) {
                            lastWrittenLsn = record.getLsn();
                        }
                        return true;
                    } catch (ClosedByInterruptException e) {
                        interrupted = true;
                        Thread.interrupted();
                        initChannel();
                    }
                }
                return false;
            } catch (IOException e) {
                return false;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Menyimpan data log sampai LSN tertentu ke disk fisik (fsync).
     */
    public boolean flushUpTo(long lsn) {
        if (lsn < 0) {
            return false;
        }
        if (lsn <= flushedLsn) {
            return true;
        }

        synchronized (ioLock) {
            if (lsn > lastWrittenLsn) {
                return false;
            }
            if (lsn <= flushedLsn) {
                return true;
            }
            boolean interrupted = Thread.interrupted();
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        if (channel == null || !channel.isOpen()) {
                            return false;
                        }
                        channel.force(true);
                        flushedLsn = Math.max(flushedLsn, lastWrittenLsn);
                        return flushedLsn >= lsn;
                    } catch (ClosedByInterruptException e) {
                        interrupted = true;
                        Thread.interrupted();
                        initChannel();
                    }
                }
                return false;
            } catch (IOException e) {
                return false;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Membaca seluruh entri log dari berkas WAL.
     * Kebijakan partial/corrupt tail: baris terakhir yang tidak lengkap/korup diabaikan dan
     * dipangkas dari disk agar append berikutnya & restart kedua tetap valid;
     * korupsi di tengah WAL langsung melempar IllegalStateException.
     */
    public List<LogRecord> readAll() {
        synchronized (ioLock) {
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    if (channel != null && channel.isOpen()) {
                        channel.force(false);
                    }
                } catch (ClosedByInterruptException e) {
                    interrupted = true;
                    Thread.interrupted();
                    initChannel();
                } catch (IOException ignored) {
                }

                if (!Files.exists(logFilePath)) {
                    return Collections.emptyList();
                }

                byte[] allBytes;
                try {
                    allBytes = Files.readAllBytes(logFilePath);
                } catch (IOException e) {
                    throw new RuntimeException("Gagal membaca berkas WAL: " + logFilePath, e);
                }

                List<RawWalLine> rawLines = scanNonEmptyLines(allBytes);
                List<LogRecord> records = new ArrayList<>();
                int totalLines = rawLines.size();
                int lastValidEndOffset = 0;
                boolean truncatedTail = false;
                boolean lastValidMissingNewline = false;

                for (int i = 0; i < totalLines; i++) {
                    RawWalLine raw = rawLines.get(i);
                    boolean isLastLine = (i == totalLines - 1);
                    try {
                        LogRecord rec = parseRecord(raw.text());
                        records.add(rec);
                        lastValidEndOffset = raw.endOffset();
                        lastValidMissingNewline = !raw.hasNewline();
                    } catch (Exception e) {
                        if (isLastLine) {
                            System.err.println("[WAL] Mengabaikan baris terakhir yang tidak lengkap: " + raw.text());
                            truncatedTail = true;
                        } else {
                            throw new IllegalStateException(
                                    "Format log tidak valid pada baris " + (i + 1) + ": " + raw.text(), e);
                        }
                    }
                }

                if (truncatedTail) {
                    truncateFileToOffset(lastValidEndOffset);
                    lastValidMissingNewline = lastValidEndOffset > 0 && allBytes[lastValidEndOffset - 1] != '\n';
                }
                if (lastValidMissingNewline && lastValidEndOffset > 0) {
                    ensureTrailingNewline();
                }

                long highestDiskLsn = 0L;
                for (LogRecord rec : records) {
                    if (rec.getLsn() > highestDiskLsn) {
                        highestDiskLsn = rec.getLsn();
                    }
                }
                if (truncatedTail) {
                    lastWrittenLsn = highestDiskLsn;
                    flushedLsn = highestDiskLsn;
                } else {
                    if (highestDiskLsn > lastWrittenLsn) {
                        lastWrittenLsn = highestDiskLsn;
                    }
                    if (highestDiskLsn > flushedLsn) {
                        flushedLsn = highestDiskLsn;
                    }
                }
                return records;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Menutup channel dan stream berkas WAL.
     */
    public void close() {
        synchronized (ioLock) {
            IOException failure = null;
            try {
                if (channel != null && channel.isOpen()) channel.force(false);
            } catch (IOException e) { failure = e; }
            try {
                if (channel != null) channel.close();
            } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            if (failure != null) throw new java.io.UncheckedIOException("Could not close WAL", failure);
        }
    }

    public Path getLogFilePath() {
        return logFilePath;
    }

    public long getFlushedLsn() {
        return flushedLsn;
    }

    public long getLastWrittenLsn() {
        return lastWrittenLsn;
    }

    private String formatRecord(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(record.getLsn()).append(FIELD_DELIMITER);
        sb.append(record.getTxId()).append(FIELD_DELIMITER);
        sb.append(record.getType().name()).append(FIELD_DELIMITER);
        sb.append(record.getTimestamp().toString());

        if (record instanceof TransactionLogRecord) {
            return sb.toString();
        } else if (record instanceof DataChangeLogRecord change) {
            sb.append(FIELD_DELIMITER).append(change.getTable()).append(FIELD_DELIMITER);
            RowId rid = change.getRowId();
            if (rid != null) {
                sb.append(rid.blockNumber()).append(FIELD_DELIMITER).append(rid.slot()).append(FIELD_DELIMITER);
            } else {
                sb.append("-1").append(FIELD_DELIMITER).append("-1").append(FIELD_DELIMITER);
            }
            sb.append(serializeImage(change.getBeforeImage())).append(FIELD_DELIMITER);
            sb.append(serializeImage(change.getAfterImage()));

            RowId newRid = change.getNewRowId();
            if (newRid != null) {
                sb.append(FIELD_DELIMITER).append(newRid.blockNumber())
                .append(FIELD_DELIMITER).append(newRid.slot());
            }
            return sb.toString();
        } else if (record instanceof CheckpointLogRecord cp) {
            sb.append(FIELD_DELIMITER).append(serializeIntList(cp.getActiveTxIds()));
            return sb.toString();
        } else {
            return sb.toString();
        }
    }

    private LogRecord parseRecord(String line) {
        List<String> tokenList = splitUnescaped(line, '|');
        String[] tokens = tokenList.toArray(new String[0]);
        if (tokens.length < 4) {
            throw new IllegalArgumentException("Field log kurang dari 4: " + line);
        }

        long lsn = Long.parseLong(tokens[0].trim());
        int txId = Integer.parseInt(tokens[1].trim());
        LogType type = LogType.valueOf(tokens[2].trim());
        Instant timestamp = Instant.parse(tokens[3].trim());

        return switch (type) {
            case BEGIN, COMMIT, ABORT -> new TransactionLogRecord(lsn, txId, type, timestamp);
            case INSERT, UPDATE, DELETE -> {
                if (tokens.length < 9) {
                    throw new IllegalArgumentException("Field DataChangeLogRecord tidak lengkap: " + line);
                }
                String table = tokens[4].trim();
                int block = Integer.parseInt(tokens[5].trim());
                int slot = Integer.parseInt(tokens[6].trim());
                RowId rid = (block >= 0 && slot >= 0) ? new RowId(table, block, slot) : null;
                RecordImage beforeImg = deserializeImage(tokens[7].trim());
                RecordImage afterImg = deserializeImage(tokens[8].trim());

                RowId newRid = null;
                if (tokens.length >= 11) {
                    int newBlock = Integer.parseInt(tokens[9].trim());
                    int newSlot = Integer.parseInt(tokens[10].trim());
                    newRid = (newBlock >= 0 && newSlot >= 0) ? new RowId(table, newBlock, newSlot) : null;
                }
                yield new DataChangeLogRecord(lsn, txId, type, timestamp, table, rid, newRid, beforeImg, afterImg);
            }
            case CHECKPOINT -> {
                if (tokens.length < 5) {
                    throw new IllegalArgumentException("Field CheckpointLogRecord tidak lengkap: " + line);
                }
                List<Integer> activeTxIds = deserializeIntList(tokens[4].trim());
                yield new CheckpointLogRecord(lsn, timestamp, activeTxIds);
            }
        };
    }

    private String serializeImage(RecordImage image) {
        if (image == null) {
            return ABSENT_TOKEN;
        }
        List<Object> values = image.values();
        if (values == null) {
            return ABSENT_TOKEN;
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            Object val = values.get(i);
            if (val == null) {
                sb.append("null");
            } else if (val instanceof String s) {
                sb.append("\"").append(escapeString(s)).append("\"");
            } else {
                sb.append(val);
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private RecordImage deserializeImage(String token) {
        if (token == null || token.equals(ABSENT_TOKEN) || token.equalsIgnoreCase("NONE")) {
            return null;
        }
        if (!token.startsWith("[") || !token.endsWith("]")) {
            throw new IllegalArgumentException("Format RecordImage tidak valid: " + token);
        }

        String inner = token.substring(1, token.length() - 1).trim();
        if (inner.isEmpty()) {
            return new RecordImage(Collections.emptyList());
        }

        List<Object> values = new ArrayList<>();
        int len = inner.length();
        int idx = 0;

        while (idx < len) {
            while (idx < len && Character.isWhitespace(inner.charAt(idx))) idx++;
            if (idx >= len) break;

            if (inner.charAt(idx) == '"') {
                idx++;
                StringBuilder sb = new StringBuilder();
                boolean escaped = false;
                while (idx < len) {
                    char c = inner.charAt(idx);
                    if (escaped) {
                        if (c == 'n') sb.append('\n');
                        else if (c == 't') sb.append('\t');
                        else sb.append(c);
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        idx++;
                        break;
                    } else {
                        sb.append(c);
                    }
                    idx++;
                }
                values.add(sb.toString());
            } else {
                int start = idx;
                while (idx < len && inner.charAt(idx) != ',') {
                    idx++;
                }
                String part = inner.substring(start, idx).trim();
                values.add(parseScalar(part));
            }

            while (idx < len && Character.isWhitespace(inner.charAt(idx))) idx++;
            if (idx < len && inner.charAt(idx) == ',') {
                idx++;
            }
        }

        return new RecordImage(values);
    }

    private Object parseScalar(String s) {
        if ("null".equalsIgnoreCase(s)) {
            return null;
        }
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        if (s.contains(".")) {
            try {
                return Float.parseFloat(s);
            } catch (NumberFormatException e) {
                return Double.parseDouble(s);
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Long.parseLong(s);
        }
    }

    private String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("|", "\\|");
    }

    private String serializeIntList(List<Integer> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private List<Integer> deserializeIntList(String token) {
        if (token == null || !token.startsWith("[") || !token.endsWith("]")) {
            return Collections.emptyList();
        }
        String inner = token.substring(1, token.length() - 1).trim();
        if (inner.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = inner.split(",");
        List<Integer> result = new ArrayList<>();
        for (String part : parts) {
            result.add(Integer.parseInt(part.trim()));
        }
        return result;
    }

    private List<String> splitUnescaped(String line, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                current.append('\\').append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == delimiter) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        tokens.add(current.toString());
        return tokens;
    }

    private List<RawWalLine> scanNonEmptyLines(byte[] bytes) {
        List<RawWalLine> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                int len = i - start;
                if (len > 0 && bytes[i - 1] == '\r') {
                    len--;
                }
                String text = new String(bytes, start, len, StandardCharsets.UTF_8);
                if (!text.trim().isEmpty()) {
                    lines.add(new RawWalLine(text, i + 1, true));
                }
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            int len = bytes.length - start;
            if (len > 0 && bytes[bytes.length - 1] == '\r') {
                len--;
            }
            String text = new String(bytes, start, len, StandardCharsets.UTF_8);
            if (!text.trim().isEmpty()) {
                lines.add(new RawWalLine(text, bytes.length, false));
            }
        }
        return lines;
    }

    private void truncateFileToOffset(int validLength) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (fos != null) {
                fos.close();
            }
        } catch (IOException ignored) {
        } finally {
            channel = null;
            fos = null;
        }
        try (FileChannel truncChannel = FileChannel.open(logFilePath, StandardOpenOption.WRITE)) {
            truncChannel.truncate(validLength);
            truncChannel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Gagal memangkas ekor korup WAL: " + logFilePath, e);
        }
        initChannel();
    }

    private void ensureTrailingNewline() {
        try {
            if (channel == null || !channel.isOpen()) {
                initChannel();
            }
            ByteBuffer nl = ByteBuffer.wrap(new byte[] { '\n' });
            while (nl.hasRemaining()) {
                channel.write(nl);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Gagal menambahkan newline penutup WAL: " + logFilePath, e);
        }
    }

    /**
     * Mengganti isi berkas WAL secara recovery-safe menggunakan berkas sementara:
     * tulis semua record -> cek setiap hasil boolean append & flushUpTo -> atomic move -> reopen channel.
     * Jika gagal di tengah jalan, berkas WAL lama tetap utuh dan exception dilempar secara eksplisit.
     */
    public void rewriteWithRecords(List<LogRecord> records) {
        synchronized (ioLock) {
            boolean interrupted = Thread.interrupted();
            Path tempPath = logFilePath.resolveSibling(logFilePath.getFileName() + ".rewrite.tmp");
            WalFileHandler staging = null;
            boolean closedCurrent = false;
            try {
                if (logFilePath.getParent() != null) {
                    Files.createDirectories(logFilePath.getParent());
                }
                Files.deleteIfExists(tempPath);

                staging = createStagingHandler(tempPath);
                long highestLsn = 0L;
                for (LogRecord rec : records) {
                    if (!staging.append(rec)) {
                        throw new RuntimeException("Gagal menulis ulang catatan WAL LSN=" + rec.getLsn());
                    }
                    if (rec.getLsn() > highestLsn) {
                        highestLsn = rec.getLsn();
                    }
                }
                if (highestLsn > 0 && !staging.flushUpTo(highestLsn)) {
                    throw new RuntimeException("Gagal flush berkas WAL sementara hingga LSN=" + highestLsn);
                }
                staging.close();
                staging = null;

                // Tutup handle WAL aktif sebelum atomic move (wajib di Windows)
                closeChannelQuietly();
                closedCurrent = true;

                try {
                    Files.move(tempPath, logFilePath,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempPath, logFilePath, StandardCopyOption.REPLACE_EXISTING);
                }

                initChannel();
                closedCurrent = false;
                lastWrittenLsn = highestLsn;
                flushedLsn = highestLsn;
            } catch (IOException e) {
                throw new RuntimeException("Gagal mengganti berkas WAL secara aman: " + logFilePath, e);
            } finally {
                if (staging != null) {
                    try {
                        staging.close();
                    } catch (RuntimeException ignored) {
                    }
                }
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                }
                if (closedCurrent) {
                    try {
                        initChannel();
                    } catch (RuntimeException ignored) {
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Hook protected agar pengujian dapat menginjeksi kegagalan append/flush saat WAL rewrite. */
    protected WalFileHandler createStagingHandler(Path tempPath) {
        return new WalFileHandler(tempPath);
    }

    private void closeChannelQuietly() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (fos != null) {
                fos.close();
            }
        } catch (IOException ignored) {
        }
        channel = null;
        fos = null;
    }
}
