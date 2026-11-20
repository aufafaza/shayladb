package mdbms.net;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class TelnetHandler {
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

    private boolean telnet;

    public TelnetHandler(Socket socket) throws IOException {
        this.input = new PushbackInputStream(
                socket.getInputStream(), 3);
        this.output = socket.getOutputStream();

        detectTelnet(socket);
    }

    public boolean isTelnet() {
        return telnet;
    }

    public int read() throws IOException {
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
