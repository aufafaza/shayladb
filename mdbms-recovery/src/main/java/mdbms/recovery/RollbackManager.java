package mdbms.recovery;

import mdbms.models.RecoveryResult;

public class RollbackManager {

    public RollbackManager() {
    }

    public RecoveryResult rollback(int txId) {
        return new RecoveryResult(true, 1L, txId, "MOCK: Rollback completed for txId=" + txId);
    }
}
