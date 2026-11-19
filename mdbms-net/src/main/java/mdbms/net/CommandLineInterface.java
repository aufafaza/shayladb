package mdbms.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import mdbms.models.query.ExecutionResult;

public class CommandLineInterface {
    private BufferedReader reader;
    private PrintWriter writer;

    private boolean lastWasCR = false;
    StringBuilder inputBuffer = new StringBuilder();
    StringBuilder queryBuffer = new StringBuilder();
    Queue<String> queuedQuery = new ArrayDeque<>();

    public CommandLineInterface(Socket socket) throws IOException {
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.writer = new PrintWriter(socket.getOutputStream(), false);
        var output = socket.getOutputStream();

        // Biar print manual
        // IAC WILL ECHO
        // IAC WILL SUPPRESS-GO-AHEAD
        output.write(new byte[] {
                (byte) 255, (byte) 251, 1,
                (byte) 255, (byte) 251, 3
        });
    }

    public String readQuery() throws IOException {
        if (!queuedQuery.isEmpty()) {
            return queuedQuery.poll();
        }

        printPrompt();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == 255) {
                handleTelnetIAC();
                continue;
            }

            if (lastWasCR && (ch == '\n' || ch == 0)) {
                lastWasCR = false;
                continue;
            }
            lastWasCR = false;

            if (ch == '\r' || ch == '\n') {
                if (ch == '\r')
                    lastWasCR = true;

                writer.println();
                writer.flush();

                flushBuffer();
                if (!queuedQuery.isEmpty()) {
                    return queuedQuery.poll();
                }
                printPrompt();
                continue;
            }

            if (ch == '\b' || ch == 127) {
                handleBackspace();
                continue;
            }

            if (ch >= 32 && ch <= 126) {
                inputBuffer.append((char) ch);
                writer.write(ch);
                writer.flush();
            }
        }
        return null;
    }

    public void printResult(ExecutionResult result) {
        if (result.success()) {
            if (!result.columns().isEmpty()) {
                printTable(result.columns(), result.rows());
            }
            writer.println(result.message());
            if (result.affectedRows() != 0) {
                writer.println("Affected rows: " + result.affectedRows());
            }
        } else {
            writer.println(result.message());
        }
        writer.flush();
    }

    private void printTable(List<String> columns, List<List<Object>> rows) {
        int N = columns.size();
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
            writer.printf("| %" + columnWidth.get(i) + "s ", columns.get(i));
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
                writer.printf("| %" + columnWidth.get(i) + "s ", String.valueOf(row.get(i)));
            }
            writer.println('|');

            for (int i = 0; i < N; i++) {
                writer.print('+');
                for (int j = 0; j < columnWidth.get(i) + 2; j++) {
                    writer.print('-');
                }
            }
            writer.println('+');
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

    // Handle Telnet IAC (255) commands safely
    private void handleTelnetIAC() throws IOException {
        int cmd = reader.read();
        if (cmd == 250) { // IAC SB (Subnegotiation): skip until IAC SE (255, 240)
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch == 255 && reader.read() == 240)
                    break;
            }
        } else if (cmd >= 251 && cmd <= 254) {
            reader.read(); // Read option byte for WILL/WONT/DO/DONT
        }
    }

    private void handleBackspace() {
        if (inputBuffer.length() > 0) {
            inputBuffer.deleteCharAt(inputBuffer.length() - 1);
            writer.write("\b \b");
            writer.flush();
        }
    }

    private void flushBuffer() {
        for (int i = 0; i < inputBuffer.length(); i++) {
            char ch = inputBuffer.charAt(i);
            queryBuffer.append(ch);
            if (ch == ';') {
                queuedQuery.add(queryBuffer.toString());
                queryBuffer.setLength(0);
            }
        }
        inputBuffer.setLength(0);
    }
}
