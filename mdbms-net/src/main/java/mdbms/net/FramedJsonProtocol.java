package mdbms.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mdbms.models.query.ExecutionResult;

/**
 * Raw TCP protocol: a four-byte unsigned, big-endian byte length followed by a
 * UTF-8 JSON document. Each request has one response frame.
 */
final class FramedJsonProtocol {
    static final int MAX_FRAME_BYTES = 1024 * 1024;
    static final int REQUEST_TIMEOUT_MILLIS = 30_000;

    private static final ScheduledExecutorService WRITE_WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mdbms-net-write-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    FramedJsonProtocol(Socket socket, InputStream input) throws IOException {
        this.socket = socket;
        this.input = input;
        this.output = socket.getOutputStream();
    }

    /** Returns null only for clean EOF before the next frame starts. */
    String readSql() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_TIMEOUT_MILLIS);
        int first = readByte(deadline);
        if (first == -1) {
            return null;
        }

        byte[] header = new byte[4];
        header[0] = (byte) first;
        readFully(header, 1, 3, deadline);
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        if (length > MAX_FRAME_BYTES) {
            throw new ProtocolException("REQUEST_TOO_LARGE",
                    "Request frame exceeds the " + MAX_FRAME_BYTES + " byte limit", true);
        }

        byte[] payload = new byte[(int) length];
        readFully(payload, 0, payload.length, deadline);
        return parseSql(decodeUtf8(payload));
    }

    void writeResponse(Response response) throws IOException {
        byte[] payload;
        try {
            payload = encode(response);
        } catch (IllegalArgumentException e) {
            payload = encode(Response.error("SERIALIZATION_ERROR", "Could not encode query result", 0));
        }
        if (payload.length > MAX_FRAME_BYTES) {
            payload = encode(Response.error("RESPONSE_TOO_LARGE",
                    "Response frame exceeds the " + MAX_FRAME_BYTES + " byte limit", 0));
        }

        ScheduledFuture<?> closeOnSlowWrite = WRITE_WATCHDOG.schedule(this::closeQuietly,
                REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        try {
            output.write(ByteBuffer.allocate(4).putInt(payload.length).array());
            output.write(payload);
            output.flush();
        } finally {
            closeOnSlowWrite.cancel(false);
        }
    }

    static Response fromExecutionResult(ExecutionResult result, long elapsedMs) {
        if (result == null) {
            return Response.error("EXECUTION_ERROR", "Query processor returned no result", elapsedMs);
        }
        if (!result.success()) {
            if (result.message() != null && result.message().startsWith("Transaction admission rejected:")) {
                return Response.error("ADMISSION_REJECTED", result.message(), elapsedMs);
            }
            return Response.error("EXECUTION_ERROR", messageOrDefault(result.message(), "Query execution failed"), elapsedMs);
        }

        List<String> columns = result.columns() == null ? List.of() : result.columns();
        List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
        if (result.affectedRows() != -1) {
            return Response.write(result.affectedRows(), result.message(), elapsedMs);
        }
        if (!columns.isEmpty() || !rows.isEmpty()) {
            return Response.result(columns, rows, result.message(), elapsedMs);
        }
        if (result.message() != null && !result.message().isBlank()) {
            return Response.ok(result.message(), elapsedMs);
        }
        return Response.empty(elapsedMs);
    }

    static String messageOrDefault(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private String decodeUtf8(byte[] payload) throws ProtocolException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(payload));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("INVALID_UTF8", "Request payload must be valid UTF-8", false);
        }
    }

    private int readByte(long deadline) throws IOException {
        setRemainingTimeout(deadline);
        try {
            return input.read();
        } catch (SocketTimeoutException e) {
            throw new ProtocolException("REQUEST_TIMEOUT", "Request was not completed within 30 seconds", true);
        }
    }

    private void readFully(byte[] bytes, int offset, int length, long deadline) throws IOException {
        int read = 0;
        while (read < length) {
            setRemainingTimeout(deadline);
            int count;
            try {
                count = input.read(bytes, offset + read, length - read);
            } catch (SocketTimeoutException e) {
                throw new ProtocolException("REQUEST_TIMEOUT", "Request was not completed within 30 seconds", true);
            }
            if (count == -1) {
                throw new ProtocolException("INCOMPLETE_REQUEST", "Connection ended in the middle of a request", true);
            }
            read += count;
        }
    }

    private void setRemainingTimeout(long deadline) throws ProtocolException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new ProtocolException("REQUEST_TIMEOUT", "Request was not completed within 30 seconds", true);
        }
        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        try {
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
        } catch (IOException e) {
            throw new ProtocolException("CONNECTION_ERROR", "Could not configure request timeout", true);
        }
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The handler will perform normal cleanup.
        }
    }

    record Response(String status, String code, String message, List<String> columns,
            List<List<Object>> rows, Integer affectedRows, long elapsedMs) {
        static Response result(List<String> columns, List<List<Object>> rows, String message, long elapsedMs) {
            return new Response("RESULT", null, message, columns, rows, null, elapsedMs);
        }

        static Response empty(long elapsedMs) {
            return new Response("EMPTY", null, null, List.of(), List.of(), null, elapsedMs);
        }

        static Response write(int affectedRows, String message, long elapsedMs) {
            return new Response("WRITE", null, message, null, null, affectedRows, elapsedMs);
        }

        static Response ok(String message, long elapsedMs) {
            return new Response("OK", null, message, null, null, null, elapsedMs);
        }

        static Response error(String code, String message, long elapsedMs) {
            return new Response("ERROR", code, message, null, null, null, elapsedMs);
        }
    }

    private static String parseSql(String json) throws ProtocolException {
        try (JsonParser parser = JSON.createParser(json)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || !root.isObject()) {
                throw new ProtocolException("INVALID_REQUEST", "Request must be a JSON object", false);
            }
            JsonNode sql = root.get("sql");
            if (sql == null || !sql.isTextual() || sql.textValue().isBlank()) {
                throw new ProtocolException("INVALID_REQUEST",
                        "Request must contain a non-empty string sql field", false);
            }
            if (parser.nextToken() != null) {
                throw new ProtocolException("INVALID_REQUEST", "Unexpected data after request object", false);
            }
            return sql.textValue();
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException("INVALID_REQUEST",
                    "Malformed JSON request: " + messageOrDefault(e.getMessage(), "invalid JSON"), false);
        }
    }

    private static byte[] encode(Response response) {
        ObjectNode json = JSON.createObjectNode();
        json.put("status", response.status());
        json.put("elapsedMs", response.elapsedMs());

        switch (response.status()) {
            case "RESULT" -> {
                if (response.columns() == null || response.rows() == null) {
                    throw new IllegalArgumentException("RESULT fields must not be null");
                }
                ArrayNode columns = json.putArray("columns");
                for (String column : response.columns()) {
                    if (column == null) throw new IllegalArgumentException("Column names must not be null");
                    columns.add(column);
                }
                ArrayNode rows = json.putArray("rows");
                for (List<Object> row : response.rows()) {
                    if (row == null) throw new IllegalArgumentException("Rows must not contain null row lists");
                    ArrayNode encodedRow = rows.addArray();
                    for (Object value : row) {
                        addValue(encodedRow, value);
                    }
                }
                optionalField(json, "message", response.message());
            }
            case "EMPTY" -> {
                json.putArray("columns");
                json.putArray("rows");
            }
            case "WRITE" -> {
                if (response.affectedRows() == null) {
                    throw new IllegalArgumentException("WRITE affectedRows must not be null");
                }
                json.put("affectedRows", response.affectedRows());
                optionalField(json, "message", response.message());
            }
            case "OK" -> optionalField(json, "message", response.message());
            case "ERROR" -> {
                json.put("code", response.code());
                json.put("message", messageOrDefault(response.message(), ""));
            }
            default -> throw new IllegalArgumentException("Unknown response status");
        }

        try {
            return JSON.writeValueAsBytes(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize response", e);
        }
    }

    private static void optionalField(ObjectNode json, String key, String value) {
        if (value != null) {
            json.put(key, value);
        }
    }

    private static void addValue(ArrayNode row, Object value) {
        if (value == null) {
            row.addNull();
        } else if (value instanceof String string) {
            row.add(string);
        } else if (value instanceof Boolean bool) {
            row.add(bool);
        } else if (value instanceof Byte number) {
            row.add(number);
        } else if (value instanceof Short number) {
            row.add(number);
        } else if (value instanceof Integer number) {
            row.add(number);
        } else if (value instanceof Long number) {
            row.add(number);
        } else if (value instanceof Float number) {
            if (!Float.isFinite(number)) throw new IllegalArgumentException("Non-finite number");
            row.add(number);
        } else if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite number");
            row.add(number);
        } else {
            throw new IllegalArgumentException("Unsupported result value type: " + value.getClass().getName());
        }
    }
}
