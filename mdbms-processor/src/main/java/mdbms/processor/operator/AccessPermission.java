package mdbms.processor.operator;

import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import mdbms.api.IConcurrencyControl;
import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;

final class AccessPermission {
    private AccessPermission() {
    }

    static void read(IConcurrencyControl concurrencyControlManager, int transactionId, DataItem item) {
        await(transactionId, () -> concurrencyControlManager.requestRead(transactionId, item));
    }

    static void write(IConcurrencyControl concurrencyControlManager, int transactionId, DataItem item,
            List<Object> oldValues, List<Object> newValues) {
        await(transactionId, () -> concurrencyControlManager.requestWrite(transactionId, item, oldValues, newValues));
    }

    private static void await(int transactionId, Supplier<AccessStatus> request) {
        AccessStatus status = request.get();
        while (status == AccessStatus.WAITING) {
            LockSupport.parkNanos(10_000_000);
            status = request.get();
        }
        if (status == AccessStatus.ABORTED) {
            throw new IllegalStateException("Transaction " + transactionId + " was aborted by CCM");
        }
    }
}
