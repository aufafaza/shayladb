package mdbms.api;

import java.util.List;
import java.util.function.Supplier;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.WriteContext;

public interface IConcurrencyControl {
    int beginTransaction();

    void commitTransaction(int txId);

    void abortTransaction(int txId, String reason);

    AccessStatus requestRead(int txId, DataItem data);

    AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal);

    AccessStatus requestWrite(int txId, DataItem data);

    default WriteContext requestWriteWithContext(int txId, DataItem data, Object oldVal, Object newVal) {
        return new WriteContext(txId, requestWrite(txId, data, oldVal, newVal), txId, data.tableName(), data.getRowId());
    }
    
    /** Protects and logs both identities before executing the physical change. */
    default <T> T executeRelocation(int txId, DataItem oldItem, DataItem newItem,
            Object oldValues, Object newValues, Supplier<T> physicalChange) {
        throw new UnsupportedOperationException("Transactional row updates are not supported");
    }

    Transaction getTransaction(int txId);

    boolean verifySchedule(List<Operation> manualSchedule);
}
