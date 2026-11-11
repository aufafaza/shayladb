package mdbms.recovery;

import mdbms.api.IDurableStorage;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CheckpointManager {
    private final LogManager logManager;
    private final IDurableStorage storageManager;
    private final RecordApplier recordApplier;

    public CheckpointManager(LogManager logManager, IDurableStorage storageManager, RecordApplier recordApplier) {
        this.logManager = logManager;
        this.storageManager = storageManager;
        this.recordApplier = recordApplier;
    }

    public void executeCheckpoint(List<Integer> activeTxIds) {
        logManager.flushUpTo(logManager.getCurrentLsn());
        
        if (storageManager != null) {
            storageManager.flushBuffer();
        }
        
        logManager.appendCheckpointLog(activeTxIds);
    }

    public void recoverToPointInTime(Instant targetTimestamp, String snapshotPath) {
        restoreSnapshot(snapshotPath); 

        List<LogRecord> allLogs = logManager.readLogs(null);
        
        Set<Integer> committedTxs = new HashSet<>();
        List<DataChangeLogRecord> recordsToRedo = new ArrayList<>();

        for (LogRecord record : allLogs) {
            if (record.getTimestamp().isAfter(targetTimestamp)) {
                break;
            }

            if (record.getType() == LogType.COMMIT) {
                committedTxs.add(record.getTxId());
            }
            
            if (record instanceof DataChangeLogRecord dataRecord) {
                recordsToRedo.add(dataRecord);
            }
        }

        for (DataChangeLogRecord record : recordsToRedo) {
            if (committedTxs.contains(record.getTxId())) {
                recordApplier.redo(record);
            }
        }
    }

    private void restoreSnapshot(String snapshotPath) {
        // TODO: Integrasikan pemindahan/penimpaan file .tbl fisik di data directory 
    }
}