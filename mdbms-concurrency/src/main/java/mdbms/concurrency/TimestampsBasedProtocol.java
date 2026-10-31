package mdbms.concurrency;

import mdbms.models.AccessStatus;
import mdbms.models.DataItem;
import mdbms.models.Transaction;

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