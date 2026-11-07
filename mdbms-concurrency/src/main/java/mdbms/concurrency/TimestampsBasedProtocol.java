package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;

public class TimestampsBasedProtocol implements ConcurrencyProtocol {
    private final TimestampManager timestampManager;

    public TimestampsBasedProtocol() {
        this(new TimestampManager());
    }

    public TimestampsBasedProtocol(TimestampManager timestampManager) {
        this.timestampManager = java.util.Objects.requireNonNull(timestampManager, "timestampManager");
    }

    @Override 
    public  AccessStatus requestRead(Transaction tx, DataItem data) {
        if (tx.getTimestamp() < timestampManager.getWriteTS(data)) return AccessStatus.ABORTED;
        timestampManager.updateReadTS(data, tx.getTimestamp());
        return AccessStatus.GRANTED;
    }

    @Override 
    public  AccessStatus requestWrite(Transaction tx, DataItem data) {
        if (tx.getTimestamp() < timestampManager.getReadTS(data)
                || tx.getTimestamp() < timestampManager.getWriteTS(data)) return AccessStatus.ABORTED;
        timestampManager.updateWriteTS(data, tx.getTimestamp());
        return AccessStatus.GRANTED;
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
        // TODO [DELETE COMMMENT KALO UDH]
        return AccessStatus.WAITING;
    }

    @Override
    public void releaseResources(Transaction tx) {

    }
}
