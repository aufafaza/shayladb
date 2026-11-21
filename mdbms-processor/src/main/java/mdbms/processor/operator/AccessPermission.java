package mdbms.processor.operator;

import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import mdbms.api.IConcurrencyControl;
import mdbms.models.data.DataItem;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.WriteContext;
import mdbms.processor.exception.TransactionAbortedException;

public final class AccessPermission {
    private static final long DEFAULT_WAIT_TIMEOUT_NANOS = 5_000_000_000L;
    private static volatile long waitTimeoutNanos = DEFAULT_WAIT_TIMEOUT_NANOS;

    private AccessPermission() {
    }

    public static void setWaitTimeoutMillis(long timeoutMillis) {
        waitTimeoutNanos = Math.max(1L, timeoutMillis) * 1_000_000L;
    }

    public static void resetWaitTimeout() {
        waitTimeoutNanos = DEFAULT_WAIT_TIMEOUT_NANOS;
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

    private static void awaitStatus(IConcurrencyControl concurrency, int transactionId, Supplier<AccessStatus> request) {
        long deadline = System.nanoTime() + waitTimeoutNanos;
        AccessStatus status = request.get();
        while (status == AccessStatus.WAITING) {
            boolean interrupted = Thread.interrupted();
            if (concurrency.isStopping() || interrupted || System.nanoTime() >= deadline) {
                try {
                    concurrency.abortTransaction(transactionId, "Access wait cancelled or timed out");
                } catch (RuntimeException ignored) {
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new TransactionAbortedException(transactionId);
            }
            LockSupport.parkNanos(2_000_000);
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