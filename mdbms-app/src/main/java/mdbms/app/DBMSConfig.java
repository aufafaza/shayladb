package mdbms.app;

public class DBMSConfig {
    private final String dataDirectory; 
    private int bufferCapacity; // let's assume this is mutable 
    private ConcurrencyAlgorithm algorithm; // agian, assume this is mutable 

    public DBMSConfig(String dataDirectory, int bufferCapacity, ConcurrencyAlgorithm alg){ 
        this.dataDirectory = dataDirectory; 
        this.bufferCapacity = bufferCapacity; 
        this.algorithm = alg; 
    }

    public String getDataDirectory(){ 
        return dataDirectory;
    }

    public ConcurrencyAlgorithm getAlgorithm(){
        return algorithm; 
    }

    public int getBufferCapacity() { 
        return bufferCapacity; 
    }


}
