package mdbms.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;

import mdbms.models.query.ExecutionResult;

public class CommandLineInterface {
    private BufferedReader reader;
    private PrintWriter writer;

    public CommandLineInterface(Socket socket) throws IOException {
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.writer = new PrintWriter(socket.getOutputStream(), true);
    }

    public String readQuery() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        int charCode;
        while ((charCode = reader.read()) != -1) {
            char ch = (char) charCode;
            stringBuilder.append(ch);
            if (ch == ';') {
                return stringBuilder.toString();
            }
        }
        return null;
    }

    public void printResult(ExecutionResult result) {
        if (result.success()) {
            printTable(result.columns(), result.rows());
            writer.println(result.message());
            if (result.affectedRows() != 0) {
                writer.println("Affected rows: " + result.affectedRows());
            }
        } else {
            writer.println(result.message());
        }
    }

    private void printTable(List<String> columns, List<List<Object>> rows) {
        int N = columns.size();
        List<Integer> columnWidth = columns.stream().map((c) -> c.length()).toList();
        for (List<Object> row : rows) {
            for (int i = 0; i < N; i++) {
                int currentWidth = columnWidth.get(i);
                columnWidth.set(i, Math.max(currentWidth, row.get(i).toString().length()));
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
                writer.printf("| %" + columnWidth.get(i) + "s ", row.get(i).toString());
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
}
