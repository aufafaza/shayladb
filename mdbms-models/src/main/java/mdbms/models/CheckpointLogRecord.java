package mdbms.models;

import java.time.Instant;
import java.util.List;

public class CheckpointLogRecord extends LogRecord {
    private final List<Integer> activeTxIds;

    public CheckpointLogRecord(long lsn, Instant timestamp, List<Integer> activeTxIds) {
        super(lsn, 0, LogType.CHECKPOINT, timestamp);
        this.activeTxIds = List.copyOf(activeTxIds);
    }

    public List<Integer> getActiveTxIds() {
        return activeTxIds;
    }
}
