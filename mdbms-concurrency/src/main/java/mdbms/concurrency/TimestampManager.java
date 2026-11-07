package mdbms.concurrency;

import mdbms.models.data.DataItem;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class TimestampManager {
    private final Map<DataItem, DataTimestamp> dataTimestamps = new ConcurrentHashMap<>();

    public long getReadTS(DataItem data) {
        return dataTimestamps.computeIfAbsent(data, ignored -> new DataTimestamp(Long.MIN_VALUE, Long.MIN_VALUE)).getReadTS();
    }

    public  long getWriteTS(DataItem data) {
        return dataTimestamps.computeIfAbsent(data, ignored -> new DataTimestamp(Long.MIN_VALUE, Long.MIN_VALUE)).getWriteTS();
    }

    public void updateReadTS(DataItem data, long timestamp) {
        dataTimestamps.computeIfAbsent(data, ignored -> new DataTimestamp(Long.MIN_VALUE, Long.MIN_VALUE))
                .setReadTS(Math.max(getReadTS(data), timestamp));
    }

    public void updateWriteTS(DataItem data, long timestamp) {
        dataTimestamps.computeIfAbsent(data, ignored -> new DataTimestamp(Long.MIN_VALUE, Long.MIN_VALUE))
                .setWriteTS(Math.max(getWriteTS(data), timestamp));
    }
    
}
