package mdbms.concurrency;

import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.DataItem;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class TimestampsBasedProtocol implements ConcurrencyProtocol {
    private final TimestampManager timestampManager;
    private final Map<Integer, Transaction> transactionRegistry = new ConcurrentHashMap<>();

    public TimestampsBasedProtocol() {
        this(new TimestampManager());
    }

    public TimestampsBasedProtocol(TimestampManager timestampManager) {
        this.timestampManager = Objects.requireNonNull(timestampManager, "timestampManager");
    }

    @Override
    public AccessStatus requestRead(Transaction tx, DataItem data) {
        if (tx == null || data == null) {
            return AccessStatus.ABORTED;
        }
        transactionRegistry.put(tx.getId(), tx);
        if (tx.getTimestamp() < timestampManager.getWriteTS(data)) {
            return AccessStatus.ABORTED;
        }
        timestampManager.updateReadTS(data, tx.getTimestamp());
        return AccessStatus.GRANTED;
    }

    @Override
    public AccessStatus requestWrite(Transaction tx, DataItem data) {
        if (tx == null || data == null) {
            return AccessStatus.ABORTED;
        }
        transactionRegistry.put(tx.getId(), tx);
        if (tx.getTimestamp() < timestampManager.getReadTS(data)
                || tx.getTimestamp() < timestampManager.getWriteTS(data)) {
            return AccessStatus.ABORTED;
        }
        timestampManager.updateWriteTS(data, tx.getTimestamp());
        return AccessStatus.GRANTED;
    }

    @Override
    public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
        Transaction tx = transactionRegistry.get(txId);
        if (tx == null) {
            tx = new Transaction(txId, System.currentTimeMillis());
            transactionRegistry.put(txId, tx);
        }
        return requestWrite(tx, data);
    }

    @Override
    public void releaseResources(Transaction tx) {
        if (tx != null) {
            transactionRegistry.remove(tx.getId());
        }
    }

    public TimestampManager getTimestampManager() {
        return timestampManager;
    }
}
