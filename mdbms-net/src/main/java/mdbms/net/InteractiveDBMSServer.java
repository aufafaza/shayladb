package mdbms.net;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;

/** Telnet server for interactive terminal clients. */
public final class InteractiveDBMSServer extends DBMSServer {
    public static final int DEFAULT_PORT = 2323;

    public InteractiveDBMSServer(
            int port,
            IQueryProcessor processor,
            IConcurrencyControl concurrencyControl) {
        super(port, processor, concurrencyControl, ClientHandler.Mode.INTERACTIVE);
    }
    public InteractiveDBMSServer(int port, IQueryProcessor processor,
            IConcurrencyControl concurrencyControl, Runnable checkpointAction) {
        super(port, processor, concurrencyControl, ClientHandler.Mode.INTERACTIVE, checkpointAction);
    }
}
