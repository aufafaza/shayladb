package mdbms.api;

import java.util.List;

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

    WriteContext requestWriteWithContext(int txId, DataItem data, Object oldVal, Object newVal);

    Transaction getTransaction(int txId);

    boolean verifySchedule(List<Operation> manualSchedule);
}
