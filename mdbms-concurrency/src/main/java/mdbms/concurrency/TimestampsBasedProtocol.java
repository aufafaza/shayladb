package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;

public class TimestampsBasedProtocol implements ConcurrencyProtocol {
    private final TimestampManaager timestampManager;

    public TimestampsBasedProtocol() {
        this(new TimestampManaager());
    }

    public TimestampsBasedProtocol(TimestampManaager timestampManager) {
        this.timestampManager = java.util.Objects.requireNonNull(timestampManager, "timestampManager");
    }

    public  AccessStatus requestRead(Transaction tx, DataItem data) {
        if (tx.getTimestamp() < timestampManager.getWriteTS(data)) return AccessStatus.ABORTED;
        timestampManager.updateReadTS(data, tx.getTimestamp());
        return AccessStatus.GRANTED;
    }

    public  AccessStatus requestWrite(Transaction tx, DataItem data) {
        if (tx.getTimestamp() < timestampManager.getReadTS(data)
                || tx.getTimestamp() < timestampManager.getWriteTS(data)) return AccessStatus.ABORTED;
        timestampManager.updateWriteTS(data, tx.getTimestamp());
        return AccessStatus.GRANTED;
    }

    public void releaseResources(Transaction tx) {

    }
}
