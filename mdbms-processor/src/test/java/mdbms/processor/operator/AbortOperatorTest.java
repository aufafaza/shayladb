package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void returnsFailureMessageWhenAbortThrows() {
        RecordingConcurrencyControl concurrencyControl = new RecordingConcurrencyControl();
        concurrencyControl.abortFailure = new IllegalStateException("transaction is closed");
        AbortOperator operator = new AbortOperator(concurrencyControl, 42);

        operator.open();
        assertNull(operator.next());
        assertEquals(1, concurrencyControl.abortCalls);
        assertEquals("ABORT failed: transaction is closed", operator.getMessage());
        assertNull(operator.next());
    }
}
