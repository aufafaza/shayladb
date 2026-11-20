package mdbms.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mdbms.models.query.ExecutionResult;
import org.junit.jupiter.api.Test;

class CommandLineInterfaceTest {

    @Test
    void readQueryReturnsQueryThroughSemicolon() throws IOException {
        CommandLineInterface cli = new CommandLineInterface(
                new TestSocket("SELECT * FROM users;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT * FROM users;", cli.readQuery());
    }

    @Test
    void readQueryReturnsOneQueryAtATime() throws IOException {
        CommandLineInterface cli = new CommandLineInterface(
                new TestSocket("SELECT 1;SELECT 2;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT 1;", cli.readQuery());
        assertEquals("SELECT 2;", cli.readQuery());
    }

    @Test
    void readQueryKeepsEscapedSingleQuoteAndSemicolonInsideString() throws IOException {
        CommandLineInterface cli = new CommandLineInterface(
                new TestSocket("SELECT 'escaped \\' quote; still inside';SELECT 2;"
                        .getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT 'escaped \\' quote; still inside';", cli.readQuery());
        assertEquals("SELECT 2;", cli.readQuery());
    }

    @Test
    void readQueryKeepsEscapedDoubleQuoteAndSemicolonInsideString() throws IOException {
        CommandLineInterface cli = new CommandLineInterface(
                new TestSocket("SELECT \"escaped \\\" quote; still inside\";SELECT 2;"
                        .getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT \"escaped \\\" quote; still inside\";", cli.readQuery());
        assertEquals("SELECT 2;", cli.readQuery());
    }

    @Test
    void readQueryReturnsNullWhenInputEnds() throws IOException {
        CommandLineInterface cli = new CommandLineInterface(new TestSocket(new byte[0]));

        assertNull(cli.readQuery());
    }

    @Test
    void readQueryGetBackspaced() throws IOException {
        CommandLineInterface cli = new CommandLineInterface(
                new TestSocket("SELECT * FROM users__\b\b;".getBytes(StandardCharsets.UTF_8)));

        assertEquals("SELECT * FROM users;", cli.readQuery());
    }

    @Test
    void printResultWritesResponseToSocket() throws IOException {
        TestSocket socket = new TestSocket(new byte[0]);
        CommandLineInterface cli = new CommandLineInterface(socket);

        cli.printResult(new ExecutionResult(
                true,
                List.of("id", "name"),
                List.of(List.of(1, "Nafis")),
                -1,
                "Query Executed"));

        String newline = System.lineSeparator();
        assertEquals(String.join(newline,
                "+----+-------+",
                "| id |  name |",
                "+----+-------+",
                "|  1 | Nafis |",
                "+----+-------+",
                "Query Executed") + newline, socket.outputAsString());
    }

    @Test
    void printResultWritesTheAffectedRows() throws IOException {
        TestSocket socket = new TestSocket(new byte[0]);
        CommandLineInterface cli = new CommandLineInterface(socket);

        // Print more than one rows
        cli.printResult(new ExecutionResult(
                true,
                List.of(),
                List.of(),
                5,
                ""));

        String newline = System.lineSeparator();
        assertEquals(String.join(newline, "(5 rows affected)", "") + newline, socket.outputAsString());

        // Print zero row
        cli.printResult(new ExecutionResult(
                true,
                List.of(),
                List.of(),
                0,
                ""));

        assertEquals(String.join(newline, "(0 rows affected)", "") + newline, socket.outputAsString());

        // Print one row
        cli.printResult(new ExecutionResult(
                true,
                List.of(),
                List.of(),
                0,
                null));

        assertEquals(String.join(newline, "(1 row affected)", "") + newline, socket.outputAsString());
    }

    @Test
    void printExecutionTimeTest() throws IOException {
        TestSocket socket = new TestSocket(new byte[0]);
        CommandLineInterface cli = new CommandLineInterface(socket);

        cli.printExecutionTime(10001000);
        String newline = System.lineSeparator();
        assertEquals(String.join(newline, "Time: " + (10001000 / 1000.0) + " ms") + newline, socket.outputAsString());
    }

    

    private static final class TestSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private TestSocket(byte[] input) {
            this.input = new ByteArrayInputStream(input);
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
    }
}
