package mdbms.recovery;

import java.time.Instant;
import mdbms.models.RecoveryResult;

public class CrashRecoveryManager {

    public CrashRecoveryManager() {
    }

    public RecoveryResult recoverOnStartup() {
        return new RecoveryResult(true, 1L, 0, "MOCK: Startup crash recovery completed");
    }

    public RecoveryResult recoverToPointInTime(Instant targetTime) {
        return new RecoveryResult(true, 1L, 0, "MOCK: Point-in-time recovery completed to " + targetTime);
    }

    public RecoveryResult recoverToTransaction(int targetTxId) {
        return new RecoveryResult(true, 1L, targetTxId, "MOCK: Recovery completed up to txId=" + targetTxId);
    }
}
