package mdbms.recovery;

import java.time.Instant;
import java.util.List;
import mdbms.models.CheckpointLogRecord;

public class CheckpointManager {

    public CheckpointManager() {
    }

    public CheckpointLogRecord createCheckpoint() {
        return new CheckpointLogRecord(1L, Instant.now(), List.of());
    }
}
