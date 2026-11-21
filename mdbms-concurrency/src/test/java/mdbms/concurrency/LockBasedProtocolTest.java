package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LockBasedProtocolTest {

    private static final DataItem FIRST_ITEM =
            new DataItem("users", new RowId("users", 0, 0));
    private static final DataItem SECOND_ITEM =
            new DataItem("users", new RowId("users", 0, 1));

    @Nested
    @DisplayName("Lock Acquisition and Compatibility")
    class LockAcquisitionTests {

    @Test
    void sharedLocksCanBeGrantedToMultipleTransactions() {
        LockBasedProtocol protocol = new LockBasedProtocol();
        Transaction first = transaction(1, 1);
        Transaction second = transaction(2, 2);

        assertEquals(AccessStatus.GRANTED, protocol.requestRead(first, FIRST_ITEM));
        assertEquals(AccessStatus.GRANTED, protocol.requestRead(second, FIRST_ITEM));
    }

    @Test
    void conflictingExclusiveRequestWaits() {
        LockBasedProtocol protocol = new LockBasedProtocol();
        Transaction holder = transaction(1, 1);
        Transaction requester = transaction(2, 2);

        assertEquals(AccessStatus.GRANTED, protocol.requestWrite(holder, FIRST_ITEM));
        assertEquals(AccessStatus.WAITING, protocol.requestRead(requester, FIRST_ITEM));
    }

    @Test
    void exclusiveLockBlocksBothSharedAndExclusiveRequests() {
        LockBasedProtocol protocol = new LockBasedProtocol();
        Transaction holder = transaction(1, 1);
        Transaction sharedRequester = transaction(2, 2);
        Transaction exclusiveRequester = transaction(3, 3);

        assertEquals(AccessStatus.GRANTED, protocol.requestWrite(holder, FIRST_ITEM));
        assertEquals(AccessStatus.WAITING, protocol.requestRead(sharedRequester, FIRST_ITEM));
        assertEquals(AccessStatus.WAITING, protocol.requestWrite(exclusiveRequester, FIRST_ITEM));
    }

    @Test
    void exclusiveLockCanBeGrantedAfterHolderReleasesResources() {
        LockManager manager = new LockManager();
        Transaction holder = transaction(1, 1);
        Transaction requester = transaction(2, 2);

        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(holder, FIRST_ITEM, Locktype.EXCLUSIVE));
        assertEquals(AccessStatus.WAITING,
                manager.acquireLock(requester, FIRST_ITEM, Locktype.EXCLUSIVE));

        manager.releaseAllLocks(holder);

        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(requester, FIRST_ITEM, Locktype.EXCLUSIVE));
    }

    @Test
    void repeatedRequestDoesNotCreateDuplicateLock() {
        LockManager manager = new LockManager();
        Transaction transaction = transaction(1, 1);

        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(transaction, FIRST_ITEM, Locktype.EXCLUSIVE));
        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(transaction, FIRST_ITEM, Locktype.EXCLUSIVE));
        assertEquals(1, manager.getLockTableSnapshot().get(FIRST_ITEM).size());
    }

    @Test
    void sharedLockCanBeUpgradedAfterOtherSharedLockIsReleased() {
        LockManager manager = new LockManager();
        Transaction first = transaction(1, 1);
        Transaction second = transaction(2, 2);

        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(first, FIRST_ITEM, Locktype.SHARED));
        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(second, FIRST_ITEM, Locktype.SHARED));
        assertEquals(AccessStatus.WAITING, manager.upgradeLock(first, FIRST_ITEM));

        manager.releaseAllLocks(second);

        assertEquals(AccessStatus.GRANTED, manager.upgradeLock(first, FIRST_ITEM));
        assertEquals(
                Locktype.EXCLUSIVE,
                manager.getLockTableSnapshot().get(FIRST_ITEM).get(0).getLocktype());
    }

    @Test
    void soleSharedLockCanBeUpgradedImmediately() {
        LockManager manager = new LockManager();
        Transaction transaction = transaction(1, 1);

        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(transaction, FIRST_ITEM, Locktype.SHARED));

        assertEquals(AccessStatus.GRANTED, manager.upgradeLock(transaction, FIRST_ITEM));
        assertEquals(
                Locktype.EXCLUSIVE,
                manager.getLockTableSnapshot().get(FIRST_ITEM).get(0).getLocktype());
    }

    @Test
    void requestingReadAfterOwnExclusiveLockRemainsGranted() {
        LockManager manager = new LockManager();
        Transaction transaction = transaction(1, 1);

        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(transaction, FIRST_ITEM, Locktype.EXCLUSIVE));
        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(transaction, FIRST_ITEM, Locktype.SHARED));
        assertEquals(1, manager.getLockTableSnapshot().get(FIRST_ITEM).size());
    }

    @Test
    void abortedTransactionCannotAcquireLock() {
        LockManager manager = new LockManager();
        Transaction transaction = transaction(1, 1);
        transaction.setState(TransactionState.FAILED);
        transaction.setState(TransactionState.ABORTED);

        assertEquals(
                AccessStatus.ABORTED,
                manager.acquireLock(transaction, FIRST_ITEM, Locktype.SHARED));
    }

    @Test
    void invalidLockRequestIsRejected() {
        LockManager manager = new LockManager();
        Transaction transaction = transaction(1, 1);

        assertThrows(IllegalArgumentException.class,
                () -> manager.acquireLock(null, FIRST_ITEM, Locktype.SHARED));
        assertThrows(IllegalArgumentException.class,
                () -> manager.acquireLock(transaction, null, Locktype.SHARED));
        assertThrows(IllegalArgumentException.class,
                () -> manager.acquireLock(transaction, FIRST_ITEM, null));
    }

    @Test
    void releasingNullTransactionIsSafe() {
        LockManager manager = new LockManager();

        assertDoesNotThrow(() -> manager.releaseAllLocks(null));
    }
    }

    @Nested
    @DisplayName("Deadlock Detection and Victim Selection")
    class DeadlockTests {

    @Test
    void nonDeadlockedWaitIsNotReportedAsDeadlock() {
        LockManager manager = new LockManager();
        Transaction holder = transaction(1, 1);
        Transaction waiter = transaction(2, 2);
        manager.acquireLock(holder, FIRST_ITEM, Locktype.EXCLUSIVE);
        manager.acquireLock(waiter, FIRST_ITEM, Locktype.SHARED);

        DeadlockHandler handler = new DeadlockHandler(manager);

        assertFalse(handler.detectDeadlock());
        assertNull(handler.chooseVictim());
    }

    @Test
    void deadlockIsDetectedAndNewestTransactionIsVictim() {
        LockManager manager = new LockManager();
        Transaction older = transaction(1, 10);
        Transaction newer = transaction(2, 20);
        manager.acquireLock(older, FIRST_ITEM, Locktype.EXCLUSIVE);
        manager.acquireLock(newer, SECOND_ITEM, Locktype.EXCLUSIVE);
        manager.acquireLock(older, SECOND_ITEM, Locktype.EXCLUSIVE);
        manager.acquireLock(newer, FIRST_ITEM, Locktype.EXCLUSIVE);

        DeadlockHandler handler = new DeadlockHandler(manager);

        assertTrue(handler.detectDeadlock());
        assertSame(newer, handler.chooseVictim());
    }

    @Test
    void protocolAbortsDeadlockVictimAndAllowsOtherTransactionToContinue() {
        LockBasedProtocol protocol = new LockBasedProtocol();
        Transaction older = transaction(1, 10);
        Transaction newer = transaction(2, 20);

        assertEquals(AccessStatus.GRANTED, protocol.requestWrite(older, FIRST_ITEM));
        assertEquals(AccessStatus.GRANTED, protocol.requestWrite(newer, SECOND_ITEM));
        assertEquals(AccessStatus.WAITING, protocol.requestWrite(older, SECOND_ITEM));
        assertEquals(AccessStatus.ABORTED, protocol.requestWrite(newer, FIRST_ITEM));

        assertEquals(TransactionState.ABORTED, newer.getState());
        assertEquals(AccessStatus.GRANTED, protocol.requestWrite(older, SECOND_ITEM));
    }

    @Test
    void threeTransactionCycleIsDetectedAndNewestTransactionIsVictim() {
        LockManager manager = new LockManager();
        Transaction first = transaction(1, 10);
        Transaction second = transaction(2, 20);
        Transaction third = transaction(3, 30);
        DataItem thirdItem = new DataItem("users", new RowId("users", 0, 2));

        manager.acquireLock(first, FIRST_ITEM, Locktype.EXCLUSIVE);
        manager.acquireLock(second, SECOND_ITEM, Locktype.EXCLUSIVE);
        manager.acquireLock(third, thirdItem, Locktype.EXCLUSIVE);
        manager.acquireLock(first, SECOND_ITEM, Locktype.EXCLUSIVE);
        manager.acquireLock(second, thirdItem, Locktype.EXCLUSIVE);
        manager.acquireLock(third, FIRST_ITEM, Locktype.EXCLUSIVE);

        DeadlockHandler handler = new DeadlockHandler(manager);

        assertTrue(handler.detectDeadlock());
        assertSame(third, handler.chooseVictim());
    }

    @Test
    void lockUpgradeDeadlockIsDetected() {
        LockManager manager = new LockManager();
        Transaction first = transaction(1, 10);
        Transaction second = transaction(2, 20);

        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(first, FIRST_ITEM, Locktype.SHARED));
        assertEquals(AccessStatus.GRANTED,
                manager.acquireLock(second, FIRST_ITEM, Locktype.SHARED));
        assertEquals(AccessStatus.WAITING, manager.upgradeLock(first, FIRST_ITEM));
        assertEquals(AccessStatus.WAITING, manager.upgradeLock(second, FIRST_ITEM));

        DeadlockHandler handler = new DeadlockHandler(manager);

        assertTrue(handler.detectDeadlock());
        assertSame(second, handler.chooseVictim());
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void victimCallbackIsCalledWhenDeadlockIsDetected() {
        LockManager manager = new LockManager();
        LockBasedProtocol protocol = new LockBasedProtocol(manager);
        Transaction older = transaction(1, 10);
        Transaction newer = transaction(2, 20);
        AtomicReference<Transaction> victimReference = new AtomicReference<>();
        protocol.setAbortHandler(victim -> {
            victimReference.set(victim);
            // The production CCM callback completes rollback before releasing locks.
            victim.setState(TransactionState.FAILED);
            manager.releaseAllLocks(victim);
            victim.setState(TransactionState.ABORTED);
        });

        assertEquals(AccessStatus.GRANTED, protocol.requestWrite(older, FIRST_ITEM));
        assertEquals(AccessStatus.GRANTED, protocol.requestWrite(newer, SECOND_ITEM));
        assertEquals(AccessStatus.WAITING, protocol.requestWrite(older, SECOND_ITEM));
        assertEquals(AccessStatus.ABORTED, protocol.requestWrite(newer, FIRST_ITEM));

        assertSame(newer, victimReference.get());
        assertEquals(TransactionState.ABORTED, newer.getState());
    }
    }

    @Nested
    @DisplayName("Input and Resource Lifecycle")
    class LifecycleTests {

    @Test
    void protocolRejectsNullTransactionOrData() {
        LockBasedProtocol protocol = new LockBasedProtocol();
        Transaction transaction = transaction(1, 1);

        assertThrows(IllegalArgumentException.class,
                () -> protocol.requestRead(null, FIRST_ITEM));
        assertThrows(IllegalArgumentException.class,
                () -> protocol.requestWrite(transaction, null));
    }

    @Test
    void releaseResourcesRemovesAllLocksHeldByTransaction() {
        LockManager manager = new LockManager();
        Transaction transaction = transaction(1, 1);
        manager.acquireLock(transaction, FIRST_ITEM, Locktype.SHARED);
        manager.acquireLock(transaction, SECOND_ITEM, Locktype.EXCLUSIVE);

        manager.releaseAllLocks(transaction);

        assertTrue(manager.getLockTableSnapshot().isEmpty());
        assertTrue(manager.getWaitingRequestsSnapshot().isEmpty());
    }

    @Test
    void transactionIdRequestUsesResolvedTransaction() {
        LockBasedProtocol protocol = new LockBasedProtocol();
        Transaction transaction = transaction(7, 7);
        Map<Integer, Transaction> transactions = new ConcurrentHashMap<>();
        transactions.put(transaction.getId(), transaction);
        protocol.setTransactionResolver(transactions::get);

        assertEquals(
                AccessStatus.GRANTED,
                protocol.requestWrite(transaction.getId(), FIRST_ITEM, "old", "new"));
        assertEquals(
                AccessStatus.ABORTED,
                protocol.requestWrite(99, FIRST_ITEM, "old", "new"));
    }
    }

    private static Transaction transaction(int id, long timestamp) {
        return new Transaction(id, timestamp);
    }
}
