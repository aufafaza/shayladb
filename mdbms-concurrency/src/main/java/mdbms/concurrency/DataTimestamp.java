package mdbms.concurrency;

public class DataTimestamp {
    private long readTS;
    private  long writeTS;

    public DataTimestamp(long readTS, long writeTS) {
        this.readTS = readTS;
        this.writeTS = writeTS;
    }

    public  long getWriteTS() {
        return  this.writeTS;
    }

    public  long getReadTS() {
        return  this.readTS;
    }

    public  void setWriteTS(long writeTS) {
        this.writeTS = writeTS;
    }

    public  void setReadTS(long readTS) {
        this.readTS = readTS;
    }
}
