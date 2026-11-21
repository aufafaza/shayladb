package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import mdbms.processor.exception.TransactionCompletionException;
import mdbms.processor.mocks.RecordingConcurrencyControl;

import org.junit.jupiter.api.Test;

class CommitOperatorTest {

    @Test
    void commitsTransactionAndReturnsSuccessMessageOnce() {
        RecordingConcurrencyControl concurrencyControl = new RecordingConcurrencyControl();
        CommitOperator operator = new CommitOperator(concurrencyControl, 42);

        operator.open();

        assertNull(operator.next());
        assertEquals(1, concurrencyControl.commitCalls);
        assertEquals(42, concurrencyControl.committedTransactionId);
        assertEquals("COMMIT transaction 42", operator.getMessage());
        assertNull(operator.next());
    }

    @Test
    void commitFailureFailsQuery() {
        RecordingConcurrencyControl concurrencyControl = new RecordingConcurrencyControl();
        IllegalStateException cause = new IllegalStateException("transaction is closed");
        concurrencyControl.commitFailure = cause;
        CommitOperator operator = new CommitOperator(concurrencyControl, 42);

        operator.open();

        // gagal harus dilempar biar QP ngembaliin hasil gagal ke klien
        TransactionCompletionException failure = assertThrows(TransactionCompletionException.class, operator::next);
        assertSame(cause, failure.getCause());
        assertEquals(1, concurrencyControl.commitCalls);
        assertEquals("COMMIT rejected: transaction is closed", operator.getMessage());
        assertNull(operator.next());
    }
}
