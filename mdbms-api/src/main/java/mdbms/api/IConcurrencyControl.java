package mdbms.api;

import java.util.List;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.Transaction;

public interface IConcurrencyControl {
    int beginTransaction();

    void commitTransaction(int txId);

    void abortTransaction(int txId, String reason);

    AccessStatus requestRead(int txId, DataItem data);

    AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal);

    AccessStatus requestWrite(int txId, DataItem data);

    Transaction getTransaction(int txId);

    boolean verifySchedule(List<Operation> manualSchedule);
}
