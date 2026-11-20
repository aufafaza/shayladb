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
        return JsonCodec.parseSql(decodeUtf8(payload));
    }

    void writeResponse(Response response) throws IOException {
        byte[] payload;
        try {
            payload = JsonCodec.encode(response).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            payload = JsonCodec.encode(Response.error("SERIALIZATION_ERROR", "Could not encode query result", 0))
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (payload.length > MAX_FRAME_BYTES) {
            payload = JsonCodec.encode(Response.error("RESPONSE_TOO_LARGE",
                    "Response frame exceeds the " + MAX_FRAME_BYTES + " byte limit", 0))
                    .getBytes(StandardCharsets.UTF_8);
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

    /** A deliberately small JSON codec for the fixed protocol schema. */
    private static final class JsonCodec {
        static String parseSql(String json) throws ProtocolException {
            JsonCursor cursor = new JsonCursor(json);
            cursor.skipWhitespace();
            cursor.expect('{');
            boolean hasSql = false;
            String sql = null;
            cursor.skipWhitespace();
            if (!cursor.consume('}')) {
                do {
                    cursor.skipWhitespace();
                    String key = cursor.readString();
                    cursor.skipWhitespace();
                    cursor.expect(':');
                    cursor.skipWhitespace();
                    if ("sql".equals(key)) {
                        if (hasSql) {
                            throw cursor.error("Request contains duplicate sql fields");
                        }
                        sql = cursor.readString();
                        hasSql = true;
                    } else {
                        cursor.skipValue();
                    }
                    cursor.skipWhitespace();
                } while (cursor.consume(','));
                cursor.expect('}');
            }
            cursor.skipWhitespace();
            if (!cursor.atEnd()) {
                throw cursor.error("Unexpected data after request object");
            }
            if (!hasSql || sql == null || sql.isBlank()) {
                throw new ProtocolException("INVALID_REQUEST", "Request must contain a non-empty string sql field", false);
            }
            return sql;
        }

        static String encode(Response response) {
            StringBuilder json = new StringBuilder("{");
            json.append("\"status\":");
            string(json, response.status());
            json.append(",\"elapsedMs\":").append(response.elapsedMs());
            switch (response.status()) {
                case "RESULT" -> {
                    json.append(",\"columns\":");
                    strings(json, response.columns());
                    json.append(",\"rows\":");
                    rows(json, response.rows());
                    optionalField(json, "message", response.message());
                }
                case "EMPTY" -> {
                    json.append(",\"columns\":[]");
                    json.append(",\"rows\":[]");
                }
                case "WRITE" -> {
                    json.append(",\"affectedRows\":").append(response.affectedRows());
                    optionalField(json, "message", response.message());
                }
                case "OK" -> optionalField(json, "message", response.message());
                case "ERROR" -> {
                    field(json, "code", response.code());
                    field(json, "message", response.message());
                }
                default -> throw new IllegalArgumentException("Unknown response status");
            }
            return json.append('}').toString();
        }

        private static void rows(StringBuilder json, List<List<Object>> rows) {
            json.append('[');
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) json.append(',');
                List<Object> row = rows.get(i);
                if (row == null) throw new IllegalArgumentException("Rows must not contain null row lists");
                json.append('[');
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) json.append(',');
                    value(json, row.get(j));
                }
                json.append(']');
            }
            json.append(']');
        }

        private static void strings(StringBuilder json, List<String> values) {
            json.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) json.append(',');
                string(json, values.get(i));
            }
            json.append(']');
        }

        private static void value(StringBuilder json, Object value) {
            if (value == null) {
                json.append("null");
            } else if (value instanceof String string) {
                string(json, string);
            } else if (value instanceof Boolean) {
                json.append(value);
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                json.append(value);
            } else if (value instanceof Float number) {
                if (!Float.isFinite(number)) throw new IllegalArgumentException("Non-finite number");
                json.append(number);
            } else if (value instanceof Double number) {
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite number");
                json.append(number);
            } else {
                throw new IllegalArgumentException("Unsupported result value type: " + value.getClass().getName());
            }
        }

        private static void optionalField(StringBuilder json, String key, String value) {
            if (value != null) field(json, key, value);
        }

        private static void field(StringBuilder json, String key, String value) {
            json.append(',');
            string(json, key);
            json.append(':');
            string(json, value == null ? "" : value);
        }

        private static void string(StringBuilder json, String value) {
            if (value == null) throw new IllegalArgumentException("Protocol strings must not be null");
            json.append('"');
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (ch < 0x20) json.append(String.format("\\u%04x", (int) ch));
                        else json.append(ch);
                    }
                }
            }
            json.append('"');
        }

        private static final class JsonCursor {
            private final String text;
            private int index;

            JsonCursor(String text) { this.text = text; }

            boolean atEnd() { return index == text.length(); }

            void skipWhitespace() {
                while (!atEnd() && Character.isWhitespace(text.charAt(index))) index++;
            }

            boolean consume(char expected) {
                if (!atEnd() && text.charAt(index) == expected) {
                    index++;
                    return true;
                }
                return false;
            }

            void expect(char expected) throws ProtocolException {
                if (!consume(expected)) throw error("Expected '" + expected + "'");
            }

            String readString() throws ProtocolException {
                expect('"');
                StringBuilder value = new StringBuilder();
                while (!atEnd()) {
                    char ch = text.charAt(index++);
                    if (ch == '"') return value.toString();
                    if (ch < 0x20) throw error("Control character in JSON string");
                    if (ch != '\\') {
                        value.append(ch);
                        continue;
                    }
                    if (atEnd()) throw error("Incomplete JSON escape");
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append((char) readHex());
                        default -> throw error("Invalid JSON escape");
                    }
                }
                throw error("Unterminated JSON string");
            }

            void skipValue() throws ProtocolException {
                skipWhitespace();
                if (atEnd()) throw error("Missing JSON value");
                char ch = text.charAt(index);
                if (ch == '"') { readString(); return; }
                if (ch == '{') {
                    index++;
                    skipWhitespace();
                    if (consume('}')) return;
                    do {
                        skipWhitespace();
                        readString();
                        skipWhitespace();
                        expect(':');
                        skipValue();
                        skipWhitespace();
                    } while (consume(','));
                    expect('}');
                    return;
                }
                if (ch == '[') {
                    index++;
                    skipWhitespace();
                    if (consume(']')) return;
                    do {
                        skipValue();
                        skipWhitespace();
                    } while (consume(','));
                    expect(']');
                    return;
                }
                int start = index;
                while (!atEnd() && " \t\r\n,]}".indexOf(text.charAt(index)) < 0) index++;
                if (start == index) throw error("Invalid JSON value");
                String literal = text.substring(start, index);
                if (!(literal.equals("true") || literal.equals("false") || literal.equals("null")
                        || literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) {
                    throw error("Invalid JSON value");
                }
            }

            int readHex() throws ProtocolException {
                if (index + 4 > text.length()) throw error("Incomplete unicode escape");
                int value = 0;
                for (int i = 0; i < 4; i++) {
                    int digit = Character.digit(text.charAt(index++), 16);
                    if (digit < 0) throw error("Invalid unicode escape");
                    value = (value << 4) | digit;
                }
                return value;
            }

            ProtocolException error(String message) {
                return new ProtocolException("INVALID_REQUEST", message, false);
            }
        }
    }
}
