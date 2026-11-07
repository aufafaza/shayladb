package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;

/** Strategy boundary implemented by lock-based or timestamp-based protocols. */
public interface ConcurrencyProtocol {
    AccessStatus requestRead(Transaction tx, DataItem data);

    AccessStatus requestWrite(Transaction tx, DataItem data);

    void releaseResources(Transaction tx);
}
