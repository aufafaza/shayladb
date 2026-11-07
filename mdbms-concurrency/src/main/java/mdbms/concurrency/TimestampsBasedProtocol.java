package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;

public class TimestampsBasedProtocol implements ConcurrencyProtocol {
    public  AccessStatus requestRead(Transaction tx, DataItem data) {
        // TODO
        return AccessStatus.GRANTED;
    }

    public  AccessStatus requestWrite(Transaction tx, DataItem data) {
        // TODO
        return AccessStatus.GRANTED;
    }

    public void releaseResources(Transaction tx) {

    }
}