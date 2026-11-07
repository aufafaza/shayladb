package mdbms.concurrency;

public class DataTimestamp {
    private long readTS;
    private  long writeTS;

    public DataTimestamp(long readTS, long writeTS) {
        this.readTS = readTS;
        this.writeTS = writeTS;
    }

    public synchronized long getWriteTS() {
        return  this.writeTS;
    }

    public synchronized long getReadTS() {
        return  this.readTS;
    }

    public synchronized void setWriteTS(long writeTS) {
        this.writeTS = writeTS;
    }

    public synchronized void setReadTS(long readTS) {
        this.readTS = readTS;
    }

    public synchronized void updateReadTS(long timestamp) {
        readTS = Math.max(readTS, timestamp);
    }

    public synchronized void updateWriteTS(long timestamp) {
        writeTS = Math.max(writeTS, timestamp);
    }
}
