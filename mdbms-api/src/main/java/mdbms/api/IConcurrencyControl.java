package mdbms.api;

import java.util.List;

import mdbms.models.AccessStatus;
import mdbms.models.DataItem;
import mdbms.models.Transaction;
import mdbms.models.Operation;

public interface IConcurrencyControl {
    int beginTransaction();

    void commitTransaction(int txId);

    void abortTransaction(int txId, String reason);

    AccessStatus requestRead(int txId, DataItem data);

    AccessStatus requestWrite(int txId, DataItem data);

    Transaction getTransaction(int txId);

    boolean verifySchedule(List<Operation> manualSchedule);
}
