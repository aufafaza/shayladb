package mdbms.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mdbms.models.query.ExecutionResult;
import org.junit.jupiter.api.Test;

class CommandLineInterfaceTest {

    @Test
    void readQueryReturnsQueryThroughSemicolon() throws IOException {
        CommandLineInterface cli = newCli(
                new TestSocket("SELECT * FROM users;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT * FROM users;", cli.readQuery());
    }

    @Test
    void readQueryReturnsOneQueryAtATime() throws IOException {
        CommandLineInterface cli = newCli(
                new TestSocket("SELECT 1;SELECT 2;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT 1;", cli.readQuery());
        assertEquals("SELECT 2;", cli.readQuery());
    }

    @Test
    void readQueryPreservesUnicodeAndMultipleRawTcpQueries() throws IOException {
        CommandLineInterface cli = newCli(
                new TestSocket("SELECT 'café 😀';SELECT '東京';".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT 'café 😀';", cli.readQuery());
        assertEquals("SELECT '東京';", cli.readQuery());
    }

    @Test
    void readQueryEchoesUnicodeInputForTelnetClients() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(new byte[] {(byte) 255, (byte) 253, 1});
        input.write("SELECT 'café 😀';\r\n".getBytes(StandardCharsets.UTF_8));

        TestSocket socket = new TestSocket(input.toByteArray());
        CommandLineInterface cli = newCli(socket);

        assertEquals("SELECT 'café 😀';", cli.readQuery());
        assertEquals("ShaylaDB> SELECT 'café 😀';\n", socket.outputAsString());
    }

    @Test
    void readQueryKeepsEscapedSingleQuoteAndSemicolonInsideString() throws IOException {
        CommandLineInterface cli = newCli(new TestSocket(
                "SELECT 'escaped \\' quote; still inside';SELECT 2;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT 'escaped \\' quote; still inside';", cli.readQuery());
        assertEquals("SELECT 2;", cli.readQuery());
    }

    @Test
    void readQueryKeepsEscapedDoubleQuoteAndSemicolonInsideString() throws IOException {
        CommandLineInterface cli = newCli(new TestSocket(
                "SELECT \"escaped \\\" quote; still inside\";SELECT 2;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT \"escaped \\\" quote; still inside\";", cli.readQuery());
        assertEquals("SELECT 2;", cli.readQuery());
    }

    @Test
    void readQueryReturnsNullWhenInputEnds() throws IOException {
        CommandLineInterface cli = newCli(new TestSocket(new byte[0]));

        assertNull(cli.readQuery());
    }

    @Test
    void readQueryGetBackspaced() throws IOException {
        CommandLineInterface cli = newCli(
                new TestSocket("SELECT * FROM users__\b\b;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT * FROM users;", cli.readQuery());
    }

    @Test
    void readQueryRejectsOversizedTelnetQuery() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(new byte[] {(byte) 255, (byte) 253, 1});
        input.write("X".repeat(17).getBytes(StandardCharsets.UTF_8));

        TestSocket socket = new TestSocket(input.toByteArray());
        TelnetHandler handler = new TelnetHandler(socket);
        CommandLineInterface cli = new CommandLineInterface(socket, handler, 16);

        ProtocolException error = assertThrows(ProtocolException.class, cli::readQuery);

        assertEquals("REQUEST_TOO_LARGE", error.code());
        assertTrue(error.closeConnection());
    }

    @Test
    void readQueryRejectsTelnetInputTrickledPastAbsoluteDeadline() throws IOException {
        byte[] telnetInput = new byte[] {(byte) 255, (byte) 253, 1};
        byte[] queryInput = "SELECT 1234567890;\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[telnetInput.length + queryInput.length];
        System.arraycopy(telnetInput, 0, input, 0, telnetInput.length);
        System.arraycopy(queryInput, 0, input, telnetInput.length, queryInput.length);

        TestSocket socket = new TestSocket(new DelayedInputStream(input, telnetInput.length, 35));
        TelnetHandler handler = new TelnetHandler(socket);
        CommandLineInterface cli = new CommandLineInterface(socket, handler, 1024, 120);

        assertThrows(SocketTimeoutException.class, cli::readQuery);
    }

    @Test
    void printResultWritesResponseToSocket() throws IOException {
        TestSocket socket = new TestSocket(new byte[0]);
        CommandLineInterface cli = newCli(socket);
        String newline = System.lineSeparator();

        cli.printResult(new ExecutionResult(
                true,
                List.of("id", "name"),
                List.of(),
                -1,
                "Query Executed"));
        assertEquals(String.join(newline,
                "+----+------+",
                "| id | name |",
                "+----+------+",
                "(0 rows)",
                "Query Executed") + newline, socket.outputAsString());

        socket.clearOutput();
        cli.printResult(new ExecutionResult(
                true,
                List.of("id", "name"),
                List.of(List.of(1, "Nafis")),
                -1,
                "Query Executed"));
        assertEquals(String.join(newline,
                "+----+-------+",
                "| id | name  |",
                "+----+-------+",
                "|  1 | Nafis |",
                "+----+-------+",
                "(1 row)",
                "Query Executed") + newline, socket.outputAsString());

        socket.clearOutput();
        cli.printResult(new ExecutionResult(
                true,
                List.of("id", "name"),
                List.of(List.of(1, "Muhammad"),
                        List.of(2, "Nafis"),
                        List.of(3, "Habibi")),
                -1,
                "Query Executed"));
        assertEquals(String.join(newline,
                "+----+----------+",
                "| id | name     |",
                "+----+----------+",
                "|  1 | Muhammad |",
                "|  2 | Nafis    |",
                "|  3 | Habibi   |",
                "+----+----------+",
                "(3 rows)",
                "Query Executed") + newline, socket.outputAsString());
    }

    @Test
    void printResultWritesTheAffectedRows() throws IOException {
        TestSocket socket = new TestSocket(new byte[0]);
        CommandLineInterface cli = newCli(socket);
        String newline = System.lineSeparator();

        // Print zero row
        cli.printResult(new ExecutionResult(
                true,
                List.of(),
                List.of(),
                0,
                ""));
        assertEquals(String.join(newline, "(0 rows affected)", "") + newline, socket.outputAsString());
        socket.clearOutput();

        // Print one row
        cli.printResult(new ExecutionResult(
                true,
                List.of(),
                List.of(),
                1,
                null));
        assertEquals(String.join(newline, "(1 row affected)", "") + newline, socket.outputAsString());
        socket.clearOutput();

        // Print more than one rows
        cli.printResult(new ExecutionResult(
                true,
                List.of(),
                List.of(),
                5,
                ""));
        assertEquals(String.join(newline, "(5 rows affected)", "") + newline, socket.outputAsString());
    }

    @Test
    void printExecutionTimeTest() throws IOException {
        TestSocket socket = new TestSocket(new byte[0]);
        CommandLineInterface cli = newCli(socket);

        cli.printExecutionTime(10001000);
        String newline = System.lineSeparator();
        assertEquals(String.join(newline, "Time: " + (10001000 / 1_000_000.0) + " ms") + newline,
                socket.outputAsString());
    }

    private static final class TestSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int timeout;

        private TestSocket(byte[] input) {
            this(new ByteArrayInputStream(input));
        }

        private TestSocket(InputStream input) {
            this.input = input;
        }

        private void clearOutput() {
            output.reset();
        }

        private String outputAsString() {
            return output.toString(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void setSoTimeout(int timeout) {
            this.timeout = timeout;
        }

        @Override
        public int getSoTimeout() {
            return timeout;
        }
    }

    private static CommandLineInterface newCli(TestSocket socket) throws IOException {
        CommandLineInterface cli = new CommandLineInterface(socket);
        socket.clearOutput();
        return cli;
    }

    private static final class DelayedInputStream extends InputStream {
        private final ByteArrayInputStream input;
        private final int delayStartingAt;
        private final long delayMillis;
        private int bytesRead;

        private DelayedInputStream(byte[] input, int delayStartingAt, long delayMillis) {
            this.input = new ByteArrayInputStream(input);
            this.delayStartingAt = delayStartingAt;
            this.delayMillis = delayMillis;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= delayStartingAt) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while simulating slow input", e);
                }
            }
            bytesRead++;
            return input.read();
        }
    }
}
