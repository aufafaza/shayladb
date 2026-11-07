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
    void readQueryReturnsNullWhenInputEnds() throws IOException {
        CommandLineInterface cli = new CommandLineInterface(new TestSocket(new byte[0]));

        assertNull(cli.readQuery());
    }

    @Test
    void printResultWritesResponseToSocket() throws IOException {
        // TODO: Check the output from cli as table 
    }

    private static final class TestSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private TestSocket(byte[] input) {
            this.input = new ByteArrayInputStream(input);
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        private String outputAsString() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
