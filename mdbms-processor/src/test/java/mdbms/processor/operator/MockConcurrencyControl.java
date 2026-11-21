package mdbms.processor.operator;

import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.WriteContext;

public class MockConcurrencyControl implements IConcurrencyControl {
    @Override public <T> T executeRelocation(int txId, DataItem oldItem, DataItem newItem,
            Object oldValues, Object newValues, java.util.function.Supplier<T> physicalChange) {
        return physicalChange.get();
    }
    @Override
    public AccessStatus requestRead(int txId, DataItem data) {
        return AccessStatus.GRANTED;
    }

    @Override
    public int beginTransaction() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commitTransaction(int txId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void abortTransaction(int txId, String reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
        return AccessStatus.GRANTED;
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data) {
        return AccessStatus.GRANTED;
    }

    @Override
    public WriteContext requestWriteWithContext(int txId, DataItem data, Object oldVal, Object newVal) {
        return new WriteContext(txId, AccessStatus.GRANTED, 0L, data.getTableName(), data.getRowId());
    }

    @Override
    public Transaction getTransaction(int txId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean verifySchedule(List<Operation> manualSchedule) {
        throw new UnsupportedOperationException();
    }
}
