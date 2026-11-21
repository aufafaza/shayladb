package mdbms.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class TelnetHandler implements AutoCloseable {
    private final class TelnetDataInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            return readDataByte();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }

            int value = readDataByte();
            if (value == -1) {
                return -1;
            }
            bytes[offset] = (byte) value;
            return 1;
        }
    }

    private static final int IAC = 255;

    private static final int WILL = 251;
    private static final int WONT = 252;
    private static final int DO = 253;
    private static final int DONT = 254;

    private static final int SB = 250;
    private static final int SE = 240;

    private static final int ECHO = 1;
    private static final int SUPPRESS_GO_AHEAD = 3;

    private final PushbackInputStream input;
    private final OutputStream output;
    private final Reader characterReader;

    private boolean telnet;
    private int pendingCodeUnit = -1;

    public TelnetHandler(Socket socket) throws IOException {
        this.input = new PushbackInputStream(
                socket.getInputStream(), 3);
        this.output = socket.getOutputStream();

        detectTelnet(socket);
        this.characterReader = new InputStreamReader(new TelnetDataInputStream(), StandardCharsets.UTF_8);
    }

    public boolean isTelnet() {
        return telnet;
    }

    public InputStream rawInput() {
        if (telnet) {
            throw new IllegalStateException("Telnet connections do not expose a raw stream");
        }
        return input;
    }

    public int read() throws IOException {
        int first = readCodeUnit();
        if (first == -1 || !Character.isHighSurrogate((char) first)) {
            return first;
        }

        int second = readCodeUnit();
        if (second != -1 && Character.isLowSurrogate((char) second)) {
            return Character.toCodePoint((char) first, (char) second);
        }
        if (second != -1) {
            pendingCodeUnit = second;
        }
        return first;
    }

    @Override
    public void close() throws IOException {
        characterReader.close();
    }

    private int readCodeUnit() throws IOException {
        if (pendingCodeUnit != -1) {
            int codeUnit = pendingCodeUnit;
            pendingCodeUnit = -1;
            return codeUnit;
        }
        return characterReader.read();
    }

    private int readDataByte() throws IOException {
        if (!telnet) {
            return input.read();
        }

        int ch;
        while ((ch = input.read()) == IAC) {
            int command = input.read();
            if (command == -1 || command == IAC) {
                return command;
            }
            if (command == SB) {
                skipSubnegotiation();
                continue;
            }
            if (isNegotiation(command)) {
                int option = input.read();
                if (option == -1)
                    return -1;
                handleNegotiation(command, option);
            }
        }
        return ch;
    }

    private void detectTelnet(Socket socket) throws IOException {
        int oldTimeout = socket.getSoTimeout();

        int first = -1;
        int command = -1;
        int option = -1;

        try {
            socket.setSoTimeout(300);
            if ((first = input.read()) == -1 || first != IAC) {
                unreadIfValid(first);
                return;
            }
            if ((command = input.read()) == -1 || !isNegotiation(command)) {
                unreadIfValid(command);
                input.unread(first);
                return;
            }
            if ((option = input.read()) == -1) {
                input.unread(command);
                input.unread(first);
                return;
            }

            telnet = true;
            handleNegotiation(command, option);
            negotiate();
        } catch (SocketTimeoutException e) {
            unreadIfValid(option);
            unreadIfValid(command);
            unreadIfValid(first);
        } finally {
            socket.setSoTimeout(oldTimeout);
        }
    }

    private void negotiate() throws IOException {
        sendCommand(WILL, ECHO);
        sendCommand(WILL, SUPPRESS_GO_AHEAD);
    }

    private void skipSubnegotiation() throws IOException {
        int ch;
        while ((ch = input.read()) != -1) {
            if (ch == IAC) {
                int next = input.read();
                if (next == SE) {
                    return;
                }
            }
        }
    }

    private boolean isNegotiation(int command) {
        return command >= WILL && command <= DONT;
    }

    private void unreadIfValid(int value) throws IOException {
        if (value != -1) {
            input.unread(value);
        }
    }

    private void handleNegotiation(int command, int option) throws IOException {
        switch (command) {
            case WILL:
                sendCommand(DONT, option);
                break;
            case WONT:
                break;
            case DO:
                if (option != ECHO && option != SUPPRESS_GO_AHEAD) {
                    sendCommand(WONT, option);
                }
                break;
            case DONT:
                break;
        }
    }

    private void sendCommand(int command, int option) throws IOException {
        output.write(new byte[] {
                (byte) IAC,
                (byte) command,
                (byte) option
        });
        output.flush();
    }
}
