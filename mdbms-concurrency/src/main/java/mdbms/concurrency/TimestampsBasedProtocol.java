package mdbms.concurrency;

import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TimestampsBasedProtocol implements ConcurrencyProtocol {
    private final TimestampManager timestampManager;
    private final Map<Integer, Transaction> transactionRegistry = new ConcurrentHashMap<>();
    private final Map<Integer, Set<DataItem>> transactionWrites = new ConcurrentHashMap<>();

    private static final Comparator<DataItem> DATA_ITEM_COMPARATOR = Comparator
            .comparing(DataItem::tableName, Comparator.nullsFirst(String::compareTo))
            .thenComparing(item -> item.rowId() != null ? item.rowId().tableName() : "", Comparator.nullsFirst(String::compareTo))
            .thenComparingInt(item -> item.rowId() != null ? item.rowId().blockNumber() : 0)
            .thenComparingInt(item -> item.rowId() != null ? item.rowId().slot() : 0);

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
        DataTimestamp dt = timestampManager.getOrCreate(data);
        return dt.checkAndApplyRead(tx);
    }

    @Override
    public AccessStatus requestWrite(Transaction tx, DataItem data) {
        if (tx == null || data == null) {
            return AccessStatus.ABORTED;
        }
        transactionRegistry.put(tx.getId(), tx);
        DataTimestamp dt = timestampManager.getOrCreate(data);
        AccessStatus status = dt.checkAndApplyWrite(tx);
        if (status == AccessStatus.GRANTED) {
            transactionWrites.computeIfAbsent(tx.getId(), k -> ConcurrentHashMap.newKeySet()).add(data);
        }
        return status;
    }

    @Override
    public AccessStatus requestWriteBatch(Transaction tx, List<DataItem> dataItems) {
        if (tx == null || dataItems == null || dataItems.isEmpty()) {
            return AccessStatus.ABORTED;
        }
        for (DataItem data : dataItems) {
            if (data == null) {
                return AccessStatus.ABORTED;
            }
        }
        transactionRegistry.put(tx.getId(), tx);

        List<DataItem> uniqueSorted = dataItems.stream()
                .distinct()
                .sorted(DATA_ITEM_COMPARATOR)
                .toList();

        return lockAndApplyBatch(tx, uniqueSorted, 0);
    }

    private AccessStatus lockAndApplyBatch(Transaction tx, List<DataItem> items, int index) {
        if (index == items.size()) {
            for (DataItem item : items) {
                DataTimestamp dt = timestampManager.getOrCreate(item);
                AccessStatus status = dt.checkWriteAllowed(tx);
                if (status != AccessStatus.GRANTED) {
                    return status;
                }
            }
            for (DataItem item : items) {
                DataTimestamp dt = timestampManager.getOrCreate(item);
                dt.applyWrite(tx);
                transactionWrites.computeIfAbsent(tx.getId(), k -> ConcurrentHashMap.newKeySet()).add(item);
            }
            return AccessStatus.GRANTED;
        }

        DataItem current = items.get(index);
        DataTimestamp dt = timestampManager.getOrCreate(current);
        synchronized (dt) {
            return lockAndApplyBatch(tx, items, index + 1);
        }
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
        if (tx == null) {
            return;
        }
        transactionRegistry.remove(tx.getId());
        Set<DataItem> writtenItems = transactionWrites.remove(tx.getId());
        if (writtenItems != null && !writtenItems.isEmpty()) {
            boolean isAbort = (tx.getState() == TransactionState.FAILED
                    || tx.getState() == TransactionState.ABORTED);
            for (DataItem data : writtenItems) {
                DataTimestamp dt = timestampManager.getDataTimestamp(data);
                if (dt != null) {
                    if (isAbort) {
                        dt.rollbackWrite(tx);
                    } else {
                        dt.commitWrite(tx);
                    }
                }
            }
        }
    }

    public TimestampManager getTimestampManager() {
        return timestampManager;
    }
}
