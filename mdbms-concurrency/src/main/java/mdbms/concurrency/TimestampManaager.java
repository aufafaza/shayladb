package mdbms.concurrency;

import mdbms.models.DataItem;

import java.util.Map;


public class TimestampManaager {
    private Map<DataItem, DataTimestamp> dataTimestamps;

    public long getReadTS(DataItem data) {
        // TODO
        return  -1;
    }

    public  long getWriteTS(DataItem data) {
        // TODO
        return  -1;
    }

    public void updateReadTS(DataItem data, long timestamp) {
        // TODO
    }

    public void updateWriteTS(DataItem data, long timestamp) {
        // TODO
    }
    
}
