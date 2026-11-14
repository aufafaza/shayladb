package mdbms.recovery;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            try {
                if (logFilePath.getParent() != null) {
                    Files.createDirectories(logFilePath.getParent());
                }
                this.fos = new FileOutputStream(logFilePath.toFile(), true);
                this.channel = fos.getChannel();
            } catch (IOException e) {
                throw new RuntimeException("Gagal membuka berkas WAL: " + logFilePath, e);
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
            try {
                if (channel == null || !channel.isOpen()) {
                    initChannel();
                }
                channel.write(ByteBuffer.wrap(bytes));
                if (record.getLsn() > lastWrittenLsn) {
                    lastWrittenLsn = record.getLsn();
                }
                return true;
            } catch (IOException e) {
                return false;
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
            try {
                if (channel != null && channel.isOpen()) {
                    channel.force(false);
                }
                flushedLsn = Math.max(flushedLsn, lastWrittenLsn);
                return flushedLsn >= lsn;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * Membaca seluruh entri log dari berkas WAL.
     */
    public List<LogRecord> readAll() {
        synchronized (ioLock) {
            try {
                if (channel != null && channel.isOpen()) {
                    channel.force(false);
                }
            } catch (IOException ignored) {
            }

            if (!Files.exists(logFilePath)) {
                return Collections.emptyList();
            }

            List<String> rawLines = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(logFilePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        rawLines.add(line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Gagal membaca berkas WAL: " + logFilePath, e);
            }

            List<LogRecord> records = new ArrayList<>();
            int totalLines = rawLines.size();

            for (int i = 0; i < totalLines; i++) {
                String line = rawLines.get(i);
                boolean isLastLine = (i == totalLines - 1);
                try {
                    LogRecord rec = parseRecord(line);
                    records.add(rec);
                } catch (Exception e) {
                    if (isLastLine) {
                        System.err.println("[WAL] Mengabaikan baris terakhir yang tidak lengkap: " + line);
                    } else {
                        throw new IllegalStateException("Format log tidak valid pada baris " + (i + 1) + ": " + line, e);
                    }
                }
            }

            return records;
        }
    }

    /**
     * Menutup channel dan stream berkas WAL.
     */
    public void close() {
        synchronized (ioLock) {
            try {
                if (channel != null && channel.isOpen()) {
                    channel.force(false);
                    channel.close();
                }
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException ignored) {
            }
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
            return sb.toString();
        } else if (record instanceof CheckpointLogRecord cp) {
            sb.append(FIELD_DELIMITER).append(serializeIntList(cp.getActiveTxIds()));
            return sb.toString();
        } else {
            return sb.toString();
        }
    }

    private LogRecord parseRecord(String line) {
        String[] tokens = line.split("\\|", -1);
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
                yield new DataChangeLogRecord(lsn, txId, type, timestamp, table, rid, beforeImg, afterImg);
            }
            case CHECKPOINT -> {
                List<Integer> activeTxIds = (tokens.length >= 5) ? deserializeIntList(tokens[4].trim()) : List.of();
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
}
