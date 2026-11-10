package mdbms.recovery;

import java.util.List;
import java.util.stream.Collectors;

public class CheckpointLogRecord extends LogRecord {
    private final List<Integer> activeTxIds;

    public CheckpointLogRecord(long lsn, long timestamp, List<Integer> activeTxIds) {
        super(lsn, timestamp, LogType.CHECKPOINT, -1); 
        this.activeTxIds = activeTxIds;
    }

    public List<Integer> getActiveTxIds() {
        return activeTxIds;
    }

    @Override
    public String toString() {
        String txList = activeTxIds.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
            
        return String.format("%d|%d|CHECKPOINT|-1|%s", getLsn(), getTimestamp(), txList);
    }
}