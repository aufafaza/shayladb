package mdbms.net;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import mdbms.models.query.ExecutionResult;

public class CommandLineInterface {
    static final int MAX_TELNET_QUERY_BYTES = FramedJsonProtocol.MAX_FRAME_BYTES;
    static final int TELNET_REQUEST_TIMEOUT_MILLIS = 5 * 60 * 1000; // 5 minutes

    private PrintWriter writer;
    private TelnetHandler telnetHandler;
    private final int maxTelnetQueryBytes;
    private final int telnetInputTimeoutMillis;

    private boolean lastWasCR = false;
    StringBuilder inputBuffer = new StringBuilder();
    StringBuilder queryBuffer = new StringBuilder();
    private int queryByteLength = 0;
    private int inputByteLength = 0;
    Queue<String> queuedQuery = new ArrayDeque<>();

    // Queue string state
    boolean isInsideString = false;
    boolean isEscaped = false;
    int stringDelimiter = '\'';

    public CommandLineInterface(Socket socket) throws IOException {
        this(socket, new TelnetHandler(socket), MAX_TELNET_QUERY_BYTES,
                TELNET_REQUEST_TIMEOUT_MILLIS);
    }

    CommandLineInterface(Socket socket, TelnetHandler telnetHandler) throws IOException {
        this(socket, telnetHandler, MAX_TELNET_QUERY_BYTES, TELNET_REQUEST_TIMEOUT_MILLIS);
    }

    CommandLineInterface(Socket socket, TelnetHandler telnetHandler, int maxTelnetQueryBytes) throws IOException {
        this(socket, telnetHandler, maxTelnetQueryBytes, TELNET_REQUEST_TIMEOUT_MILLIS);
    }

    CommandLineInterface(
            Socket socket,
            TelnetHandler telnetHandler,
            int maxTelnetQueryBytes,
            int telnetInputTimeoutMillis) throws IOException {
        this.writer = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.UTF_8);
        this.telnetHandler = telnetHandler;
        this.maxTelnetQueryBytes = maxTelnetQueryBytes;
        this.telnetInputTimeoutMillis = telnetInputTimeoutMillis;
    }

    public String readQuery() throws IOException {
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }
        return readFromTelnet();
    }

    public void printResult(ExecutionResult result) {
        if (result.success()) {
            List<String> columns = result.columns() == null ? List.of() : result.columns();
            List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
            if (!columns.isEmpty()) {
                printTable(columns, rows);
            }
            if (result.affectedRows() != -1) {
                if (result.affectedRows() == 1) {
                    writer.println("(" + result.affectedRows() + " row affected)");
                } else {
                    writer.println("(" + result.affectedRows() + " rows affected)");
                }
            }
        }
        if (result.message() == null) {
            writer.println();
        } else {
            writer.println(result.message());
        }
        writer.flush();
    }

    public void printExecutionTime(long timeNanos) {
        if (timeNanos >= 1_000_000_000) {
            writer.println("Time: " + (timeNanos / 1_000_000_000.0) + " sec");
        } else {
            writer.println("Time: " + (timeNanos / 1_000_000.0) + " ms");
        }
        writer.flush();
    }

    private void printTable(List<String> columns, List<List<Object>> rows) {
        int N = columns.size();
        if (N == 0)
            return;

        List<Integer> columnWidth = new ArrayList<>(columns.stream().map((c) -> String.valueOf(c).length()).toList());
        for (List<Object> row : rows) {
            for (int i = 0; i < N; i++) {
                int currentWidth = columnWidth.get(i);
                columnWidth.set(i, Math.max(currentWidth, String.valueOf(valueAt(row, i)).length()));
            }
        }

        for (int i = 0; i < N; i++) {
            writer.print('+');
            for (int j = 0; j < columnWidth.get(i) + 2; j++) {
                writer.print('-');
            }
        }
        writer.println('+');

        for (int i = 0; i < N; i++) {
            writer.printf("| %-" + columnWidth.get(i) + "s ", String.valueOf(columns.get(i)));
        }
        writer.println('|');

        for (int i = 0; i < N; i++) {
            writer.print('+');
            for (int j = 0; j < columnWidth.get(i) + 2; j++) {
                writer.print('-');
            }
        }
        writer.println('+');

        for (List<Object> row : rows) {
            for (int i = 0; i < N; i++) {
                Object value = valueAt(row, i);
                if (value instanceof Number) {
                    writer.printf("| %" + columnWidth.get(i) + "s ", String.valueOf(value));
                } else {
                    writer.printf("| %-" + columnWidth.get(i) + "s ", String.valueOf(value));
                }
            }
            writer.println('|');
        }

        if (rows.size() > 0) {
            for (int i = 0; i < N; i++) {
                writer.print('+');
                for (int j = 0; j < columnWidth.get(i) + 2; j++) {
                    writer.print('-');
                }
            }
            writer.println('+');
        }

        if (rows.size() == 1) {
            writer.println("(" + rows.size() + " row)");
        } else {
            writer.println("(" + rows.size() + " rows)");
        }
    }

    private Object valueAt(List<Object> row, int index) {
        return row != null && index < row.size() ? row.get(index) : null;
    }

    private void printPrompt() {
        if (queryBuffer.isEmpty()) {
            writer.print("ShaylaDB> ");
        } else {
            writer.print("\t-> ");
        }
        writer.flush();
    }

    private String readFromTelnet() throws IOException {
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }

        printPrompt();
        long deadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(telnetInputTimeoutMillis);
        int ch;
        while ((ch = telnetHandler.read(deadlineNanos)) != -1) {
            // Extra char after \r
            if (lastWasCR && (ch == '\n' || ch == 0)) {
                lastWasCR = false;
                continue;
            }
            lastWasCR = false;

            // Endline
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r')
                    lastWasCR = true;

                String endline = System.lineSeparator();
                for (int i = 0; i < endline.length(); i++) {
                    writeToTelnet(endline.codePointAt(i));
                }

                if (!queryBuffer.isEmpty()) {
                    inputBuffer.insert(0, '\n');
                    inputByteLength++;
                    ensureTelnetQuerySize();
                }

                flushBuffer();
                if (!queuedQuery.isEmpty()) {
                    return queuedQuery.poll();
                }
                printPrompt();
                continue;
            }

            // Backspace
            if (ch == '\b' || ch == 127) {
                if (inputBuffer.length() > 0) {
                    inputByteLength -= deleteLastCodePoint(inputBuffer);
                    writeToTelnet("\b \b");
                }
                continue;
            }

            if (!Character.isISOControl(ch)) {
                inputBuffer.appendCodePoint(ch);
                inputByteLength += utf8ByteLength(ch);
                ensureTelnetQuerySize();
                writeToTelnet(ch);
            }
        }

        flushBuffer();
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }
        return null;
    }

    private int deleteLastCodePoint(StringBuilder buffer) {
        if (buffer.length() > 0) {
            int start = buffer.offsetByCodePoints(buffer.length(), -1);
            int codePoint = buffer.codePointAt(start);
            buffer.delete(start, buffer.length());
            return utf8ByteLength(codePoint);
        }
        return 0;
    }

    private void flushBuffer() {
        for (int i = 0; i < inputBuffer.length();) {
            int codePoint = inputBuffer.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            char ch = (char) codePoint;
            if (queryBuffer.isEmpty() && !isInsideString && Character.isWhitespace(codePoint)) {
                i += charCount;
                continue;
            }
            if (charCount == 1) {
                queryBuffer.append((char) codePoint);
            } else {
                queryBuffer.appendCodePoint(codePoint);
            }
            queryByteLength += utf8ByteLength(codePoint);
            i += charCount;
            if (isEscaped) {
                isEscaped = false;
                continue;
            }
            if (ch == '\\') {
                isEscaped = true;
            } else if (isInsideString && ch == stringDelimiter) {
                isInsideString = false;
            } else if (!isInsideString && (ch == '\'' || ch == '\"')) {
                isInsideString = true;
                stringDelimiter = ch;
            } else if (!isInsideString && ch == ';') {
                queuedQuery.add(queryBuffer.toString());
                queryBuffer.setLength(0);
                queryByteLength = 0;
            }
        }
        inputBuffer.setLength(0);
        inputByteLength = 0;
    }

    private void ensureTelnetQuerySize() throws ProtocolException {
        if (queryByteLength + inputByteLength > maxTelnetQueryBytes) {
            throw new ProtocolException("REQUEST_TOO_LARGE",
                    "Telnet query exceeds the " + maxTelnetQueryBytes + " byte limit", true);
        }
    }

    private int utf8ByteLength(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }

    private void writeToTelnet(int codePoint) {
        if (telnetHandler.isTelnet()) {
            writer.write(Character.toChars(codePoint));
            writer.flush();
        }
    }

    private void writeToTelnet(String string) {
        if (telnetHandler.isTelnet()) {
            writer.write(string);
            writer.flush();
        }
    }
}
