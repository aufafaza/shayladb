package mdbms.api;

import mdbms.models.AccessStatus;
import mdbms.models.DataItem;
import mdbms.models.Transaction;

public interface IConcurrencyControl {
    int beginTransaction();

    void commitTransaction(int txId);

    void abortTransaction(int txId, String reason);

    AccessStatus requestRead(int txId, DataItem data);

    AccessStatus requestWrite(int txId, DataItem data);

    Transaction getTransaction(int txId);
}
