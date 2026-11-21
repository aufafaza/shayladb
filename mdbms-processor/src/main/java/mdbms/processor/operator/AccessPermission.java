package mdbms.processor.operator;

import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import mdbms.api.IConcurrencyControl;
import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.WriteContext;
import mdbms.processor.exception.TransactionAbortedException;

final class AccessPermission {
    private AccessPermission() {
    }

    static void read(IConcurrencyControl concurrencyControlManager, int transactionId, DataItem item) {
        awaitStatus(concurrencyControlManager, transactionId,
                () -> concurrencyControlManager.requestRead(transactionId, item));
    }

    static void lockWrite(IConcurrencyControl concurrencyControlManager, int transactionId, DataItem item) {
        awaitStatus(concurrencyControlManager, transactionId,
                () -> concurrencyControlManager.requestWrite(transactionId, item));
    }

    static WriteContext writeWithContext(IConcurrencyControl concurrencyControlManager, int transactionId,
            DataItem item, List<Object> oldValues, List<Object> newValues) {
        WriteContext ctx = concurrencyControlManager.requestWriteWithContext(transactionId, item, oldValues, newValues);
        while (ctx.getStatus() == AccessStatus.WAITING) {
            if (Thread.currentThread().isInterrupted()) {
                safeAbort(concurrencyControlManager, transactionId, "Write wait interrupted");
                throw new TransactionAbortedException(transactionId);
            }
            LockSupport.parkNanos(10_000_000);
            ctx = concurrencyControlManager.requestWriteWithContext(transactionId, item, oldValues, newValues);
        }
        if (ctx.getStatus() != AccessStatus.GRANTED) {
            throw new TransactionAbortedException(transactionId);
        }
        return ctx;
    }

    static void write(IConcurrencyControl concurrencyControlManager, int transactionId, DataItem item,
            List<Object> oldValues, List<Object> newValues) {
        writeWithContext(concurrencyControlManager, transactionId, item, oldValues, newValues);
    }

    private static void awaitStatus(IConcurrencyControl concurrencyControlManager, int transactionId,
            Supplier<AccessStatus> request) {
        AccessStatus status = request.get();
        while (status == AccessStatus.WAITING) {
            if (Thread.currentThread().isInterrupted()) {
                safeAbort(concurrencyControlManager, transactionId, "Access wait interrupted");
                throw new TransactionAbortedException(transactionId);
            }
            LockSupport.parkNanos(10_000_000);
            status = request.get();
        }
        if (status == AccessStatus.ABORTED) {
            throw new TransactionAbortedException(transactionId);
        }
    }

    private static void safeAbort(IConcurrencyControl concurrencyControlManager, int transactionId, String reason) {
        try {
            concurrencyControlManager.abortTransaction(transactionId, reason);
        } catch (RuntimeException ignored) {
        }
    }
}