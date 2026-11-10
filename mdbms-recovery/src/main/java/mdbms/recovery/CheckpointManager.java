package mdbms.recovery;

import mdbms.api.IDurableStorage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CheckpointManager {
    private final LogManager logManager;
    private final IDurableStorage storageManager;
    private final RecordApplier recordApplier; // Kelas untuk Undo/Redo (dari PIC 13524024)

    public CheckpointManager(LogManager logManager, IDurableStorage storageManager, RecordApplier recordApplier) {
        this.logManager = logManager;
        this.storageManager = storageManager;
        this.recordApplier = recordApplier;
    }

    public void createCheckpoint() {
        logManager.flushLog(); 
        
        storageManager.flushBuffer();
        
        List<Integer> activeTxIds = logManager.getActiveTransactions(); 
        CheckpointLogRecord cpRecord = new CheckpointLogRecord(
            logManager.generateNextLsn(), 
            System.currentTimeMillis(), 
            activeTxIds
        );
        
        logManager.appendRecord(cpRecord);
        logManager.flushLog(); 
    }

    public void recoverToPointInTime(long targetTimestamp, String snapshotPath) {
        restoreSnapshot(snapshotPath); 

        LogReader reader = logManager.getReader();
        Set<Integer> committedTxs = new HashSet<>();
        List<LogRecord> recordsToRedo = new ArrayList<>();

        while (reader.hasNext()) {
            LogRecord record = reader.next();
            
            if (record.getTimestamp() > targetTimestamp) {
                break; 
            }

            if (record.getType() == LogType.COMMIT) {
                committedTxs.add(record.getTxId());
            }
            
            if (record.isDataRecord()) {
                recordsToRedo.add(record);
            }
        }

        for (LogRecord record : recordsToRedo) {
            if (committedTxs.contains(record.getTxId())) {
                recordApplier.redo(record);
            }
        }
    }

    private void restoreSnapshot(String snapshotPath) {
        
    }
}