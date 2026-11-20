package mdbms.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import mdbms.models.query.ExecutionResult;

public class CommandLineInterface {
    private PrintWriter writer;
    private TelnetHandler telnetHandler;

    private boolean lastWasCR = false;
    StringBuilder inputBuffer = new StringBuilder();
    StringBuilder queryBuffer = new StringBuilder();
    Queue<String> queuedQuery = new ArrayDeque<>();

    // Queue string state
    boolean isInsideString = false;
    boolean isEscaped = false;
    int stringDelimiter = '\'';

    public CommandLineInterface(Socket socket) throws IOException {
        this.writer = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.UTF_8);
        this.telnetHandler = new TelnetHandler(socket);
    }

    public String readQuery() throws IOException {
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }
        if (telnetHandler.isTelnet()) {
            return readFromTelnet();
        } else {
            return readFromRawTCP();
        }
    }

    public void printResult(ExecutionResult result) {
        if (result.success()) {
            if (!result.columns().isEmpty()) {
                printTable(result.columns(), result.rows());
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
            writer.println("Time: " + (timeNanos / 1000000.0) + " sec");
        } else {
            writer.println("Time: " + (timeNanos / 1000.0) + " ms");
        }
        writer.flush();
    }

    private void printTable(List<String> columns, List<List<Object>> rows) {
        int N = columns.size();
        if (N == 0)
            return;

        List<Integer> columnWidth = new ArrayList<>(columns.stream().map((c) -> c.length()).toList());
        for (List<Object> row : rows) {
            for (int i = 0; i < N; i++) {
                int currentWidth = columnWidth.get(i);
                columnWidth.set(i, Math.max(currentWidth, String.valueOf(row.get(i)).length()));
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
            writer.printf("| %-" + columnWidth.get(i) + "s ", columns.get(i));
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
                if (row.get(i) instanceof Number) {
                    writer.printf("| %" + columnWidth.get(i) + "s ", String.valueOf(row.get(i)));
                } else {
                    writer.printf("| %-" + columnWidth.get(i) + "s ", String.valueOf(row.get(i)));
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

    private void printPrompt() {
        if (queryBuffer.isEmpty()) {
            writer.print("ShaylaDB> ");
        } else {
            writer.print("\t-> ");
        }
        writer.flush();
    }

    private String readFromRawTCP() throws IOException {
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }

        int ch;
        while ((ch = telnetHandler.read()) != -1) {
            // Extra char after \r
            if (lastWasCR && (ch == '\n' || ch == '\0')) {
                lastWasCR = false;
                continue;
            }
            lastWasCR = false;

            // Endline
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r')
                    lastWasCR = true;
                queryBuffer.append('\n');
                continue;
            }

            // Backspace
            if (ch == '\b' || ch == 127) {
                deleteLastCodePoint(queryBuffer);
                continue;
            }

            if (!Character.isISOControl(ch)) {
                queryBuffer.appendCodePoint(ch);
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
                    return queuedQuery.poll();
                }
            }
        }
        return null;
    }

    private String readFromTelnet() throws IOException {
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }

        printPrompt();
        int ch;
        while ((ch = telnetHandler.read()) != -1) {
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

                writeToTelnet('\n');
                inputBuffer.append('\n');

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
                    deleteLastCodePoint(inputBuffer);
                    writeToTelnet("\b \b");
                }
                continue;
            }

            if (!Character.isISOControl(ch)) {
                inputBuffer.appendCodePoint(ch);
                writeToTelnet(ch);
            }
        }

        flushBuffer();
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }
        return null;
    }

    private void deleteLastCodePoint(StringBuilder buffer) {
        if (buffer.length() > 0) {
            int start = buffer.offsetByCodePoints(buffer.length(), -1);
            buffer.delete(start, buffer.length());
        }
    }

    private void flushBuffer() {
        for (int i = 0; i < inputBuffer.length(); i++) {
            char ch = inputBuffer.charAt(i);
            queryBuffer.append(ch);
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
            }
        }
        inputBuffer.setLength(0);
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
