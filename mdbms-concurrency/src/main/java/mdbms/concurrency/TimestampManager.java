package mdbms.concurrency;

import mdbms.models.data.DataItem;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class TimestampManager {
    private final Map<DataItem, DataTimestamp> dataTimestamps = new ConcurrentHashMap<>();
    private final long defaultInitialTimestamp;

    public TimestampManager() {
        this(0L);
    }

    public TimestampManager(long defaultInitialTimestamp) {
        this.defaultInitialTimestamp = defaultInitialTimestamp;
    }

    public long getReadTS(DataItem data) {
        Objects.requireNonNull(data, "data");
        DataTimestamp dt = dataTimestamps.get(data);
        return dt != null ? dt.getReadTS() : defaultInitialTimestamp;
    }

    public long getWriteTS(DataItem data) {
        Objects.requireNonNull(data, "data");
        DataTimestamp dt = dataTimestamps.get(data);
        return dt != null ? dt.getWriteTS() : defaultInitialTimestamp;
    }

    public void updateReadTS(DataItem data, long timestamp) {
        Objects.requireNonNull(data, "data");
        dataTimestamps.computeIfAbsent(data, ignored -> new DataTimestamp(defaultInitialTimestamp, defaultInitialTimestamp))
                .updateReadTS(timestamp);
    }

    public void updateWriteTS(DataItem data, long timestamp) {
        Objects.requireNonNull(data, "data");
        dataTimestamps.computeIfAbsent(data, ignored -> new DataTimestamp(defaultInitialTimestamp, defaultInitialTimestamp))
                .updateWriteTS(timestamp);
    }

    public void clear() {
        dataTimestamps.clear();
    }

    public boolean isEmpty() {
        return dataTimestamps.isEmpty();
    }

    public int size() {
        return dataTimestamps.size();
    }

    public void remove(DataItem data) {
        if (data != null) {
            dataTimestamps.remove(data);
        }
    }
}
