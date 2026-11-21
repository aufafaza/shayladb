package mdbms.net;

import java.io.IOException;

/** A client-visible framing or request validation failure. */
final class ProtocolException extends IOException {
    private final String code;
    private final boolean closeConnection;

    ProtocolException(String code, String message, boolean closeConnection) {
        super(message);
        this.code = code;
        this.closeConnection = closeConnection;
    }

    String code() {
        return code;
    }

    boolean closeConnection() {
        return closeConnection;
    }
}
