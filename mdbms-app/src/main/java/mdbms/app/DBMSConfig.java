package mdbms.app;

import java.util.Objects;

public class DBMSConfig {
    private static final String DEFAULT_DATA_DIRECTORY = "data";
    private static final int DEFAULT_BUFFER_CAPACITY = 64;
    private static final int DEFAULT_PORT = 7654;
    private static final int DEFAULT_INTERACTIVE_PORT = 2323;
    // LOCK_BASED becomes usable once LockBasedProtocol implements ConcurrencyProtocol
    private static final ConcurrencyAlgorithm DEFAULT_ALGORITHM = ConcurrencyAlgorithm.TIMESTAMP_BASED;

    private final String dataDirectory;
    private final int bufferCapacity;
    private final ConcurrencyAlgorithm concurrencyAlgorithm;
    private final int port;
    private final int interactivePort;

    public DBMSConfig(){
        this(DEFAULT_DATA_DIRECTORY, DEFAULT_BUFFER_CAPACITY, DEFAULT_ALGORITHM);
    }

    public DBMSConfig(String dataDirectory, int bufferCapacity, ConcurrencyAlgorithm concurrencyAlgorithm){
        this(dataDirectory, bufferCapacity, concurrencyAlgorithm, DEFAULT_PORT, DEFAULT_INTERACTIVE_PORT);
    }

    public DBMSConfig(String dataDirectory, int bufferCapacity, ConcurrencyAlgorithm concurrencyAlgorithm, int port){
        this(dataDirectory, bufferCapacity, concurrencyAlgorithm, port, DEFAULT_INTERACTIVE_PORT);
    }

    public DBMSConfig(
            String dataDirectory,
            int bufferCapacity,
            ConcurrencyAlgorithm concurrencyAlgorithm,
            int port,
            int interactivePort){
        if (bufferCapacity <= 0) throw new IllegalArgumentException("bufferCapacity must be positive");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
        if (interactivePort < 1 || interactivePort > 65535) {
            throw new IllegalArgumentException("interactivePort must be between 1 and 65535");
        }
        if (port == interactivePort) throw new IllegalArgumentException("JSON and interactive ports must be different");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.bufferCapacity = bufferCapacity;
        this.concurrencyAlgorithm = Objects.requireNonNull(concurrencyAlgorithm, "concurrencyAlgorithm");
        this.port = port;
        this.interactivePort = interactivePort;
    }

    public String getDataDirectory(){
        return dataDirectory;
    }

    public ConcurrencyAlgorithm getConcurrencyAlgorithm(){
        return concurrencyAlgorithm;
    }

    public int getBufferCapacity(){
        return bufferCapacity;
    }

    public int getPort(){
        return port;
    }

    public int getInteractivePort() {
        return interactivePort;
    }
}
