package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import mdbms.processor.exception.TransactionCompletionException;
import mdbms.processor.mocks.RecordingConcurrencyControl;

import org.junit.jupiter.api.Test;

class AbortOperatorTest {

    @Test
    void abortsTransactionAndReturnsSuccessMessageOnce() {
        RecordingConcurrencyControl concurrencyControl = new RecordingConcurrencyControl();
        AbortOperator operator = new AbortOperator(concurrencyControl, 42);

        operator.open();
        assertNull(operator.next());
        assertEquals(1, concurrencyControl.abortCalls);
        assertEquals(42, concurrencyControl.abortedTransactionId);
        assertEquals("ABORT requested by client", concurrencyControl.abortReason);
        assertEquals("ABORT transaction 42", operator.getMessage());
        assertNull(operator.next());
    }

    @Test
    void abortFailureFailsQuery() {
        RecordingConcurrencyControl concurrencyControl = new RecordingConcurrencyControl();
        IllegalStateException cause = new IllegalStateException("transaction is closed");
        concurrencyControl.abortFailure = cause;
        AbortOperator operator = new AbortOperator(concurrencyControl, 42);

        operator.open();

        // gagal harus dilempar biar QP ngembaliin hasil gagal ke klien
        TransactionCompletionException failure = assertThrows(TransactionCompletionException.class, operator::next);
        assertSame(cause, failure.getCause());
        assertEquals(1, concurrencyControl.abortCalls);
        assertEquals("ABORT failed: transaction is closed", operator.getMessage());
        assertNull(operator.next());
    }
}
