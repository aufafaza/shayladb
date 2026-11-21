package mdbms.api;

import java.util.List;
import java.util.function.Supplier;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.WriteContext;

public interface IConcurrencyControl {
    /** Cooperative cancellation used while closing the engine. */
    default boolean isStopping() { return false; }

    int beginTransaction();

    void commitTransaction(int txId);

    void abortTransaction(int txId, String reason);

    AccessStatus requestRead(int txId, DataItem data);

    AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal);

    AccessStatus requestWrite(int txId, DataItem data);

    default WriteContext requestWriteWithContext(int txId, DataItem data, Object oldVal, Object newVal) {
        AccessStatus status = requestWrite(txId, data, oldVal, newVal);
        if (status != AccessStatus.GRANTED) {
            return WriteContext.denied(txId, status, data != null ? data.tableName() : null,
                    data != null ? data.getRowId() : null);
        }
        return new WriteContext(txId, status, 0L, data != null ? data.tableName() : null,
                data != null ? data.getRowId() : null);
    }

    /** Protects and logs both identities before executing the physical change. */
    default <T> T executeRelocation(int txId, DataItem oldItem, DataItem newItem,
            Object oldValues, Object newValues, Supplier<T> physicalChange) {
        throw new UnsupportedOperationException("Transactional row updates are not supported");
    }

    Transaction getTransaction(int txId);

    boolean verifySchedule(List<Operation> manualSchedule);
}
