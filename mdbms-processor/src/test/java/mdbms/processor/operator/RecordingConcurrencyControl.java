package mdbms.processor.operator;

import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.Transaction;

final class RecordingConcurrencyControl implements IConcurrencyControl {
    int commitCalls;
    int committedTransactionId;
    RuntimeException commitFailure;
    int abortCalls;
    int abortedTransactionId;
    String abortReason;
    RuntimeException abortFailure;

    @Override
    public int beginTransaction() {
        throw new UnsupportedOperationException("Not used in this test");
    }

    @Override
    public void commitTransaction(int txId) {
        commitCalls++;
        committedTransactionId = txId;
        if (commitFailure != null) {
            throw commitFailure;
        }
    }

    @Override
    public void abortTransaction(int txId, String reason) {
        abortCalls++;
        abortedTransactionId = txId;
        abortReason = reason;
        if (abortFailure != null) {
            throw abortFailure;
        }
    }

    @Override
    public AccessStatus requestRead(int txId, DataItem data) {
        throw new UnsupportedOperationException("Not used in this test");
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
        throw new UnsupportedOperationException("Not used in this test");
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data) {
        throw new UnsupportedOperationException("Not used in this test");
    }

    @Override
    public Transaction getTransaction(int txId) {
        throw new UnsupportedOperationException("Not used in this test");
    }

    @Override
    public boolean verifySchedule(List<Operation> manualSchedule) {
        throw new UnsupportedOperationException("Not used in this test");
    }
}
