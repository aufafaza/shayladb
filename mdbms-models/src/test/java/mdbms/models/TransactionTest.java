package mdbms.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    void constructorSetsIdAndTimestamp() {
        Transaction tx = new Transaction(7, 1000L);

        assertEquals(7, tx.getId());
        assertEquals(1000L, tx.getTimestamp());
    }

    @Test
    void newTransactionStartsActive() {
        assertEquals(TransactionState.ACTIVE, new Transaction(1, 0L).getState());
    }

    @Test
    void setStateChangesState() {
        Transaction tx = new Transaction(1, 0L);

        tx.setState(TransactionState.PARTIALLY_COMMITTED);

        assertEquals(TransactionState.PARTIALLY_COMMITTED, tx.getState());
    }

    @Test
    void setStateRejectsInvalidTransition() {
        Transaction tx = new Transaction(1, 0L);

        assertThrows(IllegalStateException.class, () -> tx.setState(TransactionState.COMMITTED));
    }

    @Test
    void constructorRejectsNegativeId() {
        assertThrows(IllegalArgumentException.class, () -> new Transaction(-1, 0L));
    }
}
