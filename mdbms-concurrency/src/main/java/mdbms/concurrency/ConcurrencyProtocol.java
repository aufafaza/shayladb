package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import java.util.List;

/** Strategy boundary implemented by lock-based or timestamp-based protocols. */
public interface ConcurrencyProtocol {
    AccessStatus requestRead(Transaction tx, DataItem data);

    AccessStatus requestWrite(Transaction tx, DataItem data);

    /** Acquires write protection for all items as one protocol operation. */
    default AccessStatus requestWriteBatch(Transaction tx, List<DataItem> dataItems) {
        for (DataItem data : dataItems) {
            AccessStatus status = requestWrite(tx, data);
            if (status != AccessStatus.GRANTED) return status;
        }
        return AccessStatus.GRANTED;
    }

    AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) ;

    void releaseResources(Transaction tx);
}
