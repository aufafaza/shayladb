package mdbms.app;

import java.util.Objects;

public class DBMSConfig {
    private static final String DEFAULT_DATA_DIRECTORY = "data";
    private static final int DEFAULT_BUFFER_CAPACITY = 64;
    private static final int DEFAULT_PORT = 7654;
    // LOCK_BASED becomes usable once LockBasedProtocol implements ConcurrencyProtocol
    private static final ConcurrencyAlgorithm DEFAULT_ALGORITHM = ConcurrencyAlgorithm.TIMESTAMP_BASED;

    private final String dataDirectory;
    private final int bufferCapacity;
    private final ConcurrencyAlgorithm concurrencyAlgorithm;
    private final int port;

    public DBMSConfig(){
        this(DEFAULT_DATA_DIRECTORY, DEFAULT_BUFFER_CAPACITY, DEFAULT_ALGORITHM);
    }

    public DBMSConfig(String dataDirectory, int bufferCapacity, ConcurrencyAlgorithm concurrencyAlgorithm){
        this(dataDirectory, bufferCapacity, concurrencyAlgorithm, DEFAULT_PORT);
    }

    public DBMSConfig(String dataDirectory, int bufferCapacity, ConcurrencyAlgorithm concurrencyAlgorithm, int port){
        if (bufferCapacity <= 0) throw new IllegalArgumentException("bufferCapacity must be positive");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.bufferCapacity = bufferCapacity;
        this.concurrencyAlgorithm = Objects.requireNonNull(concurrencyAlgorithm, "concurrencyAlgorithm");
        this.port = port;
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
}
