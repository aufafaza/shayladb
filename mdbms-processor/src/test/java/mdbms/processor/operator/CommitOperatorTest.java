package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import mdbms.processor.IResultRow;

import org.junit.jupiter.api.Test;

class CommitOperatorTest {

    @Test
    void commitsTransactionAndReturnsSuccessMessageOnce() {
        RecordingConcurrencyControl concurrencyControl = new RecordingConcurrencyControl();
        CommitOperator operator = new CommitOperator(concurrencyControl, 42);

        operator.open();
        IResultRow result = operator.next();

        assertEquals(1, concurrencyControl.commitCalls);
        assertEquals(42, concurrencyControl.committedTransactionId);
        assertEquals("COMMIT transaction 42", result.get("message"));
        assertNull(operator.next());
    }

    @Test
    void returnsRejectedMessageWhenCommitThrows() {
        RecordingConcurrencyControl concurrencyControl = new RecordingConcurrencyControl();
        concurrencyControl.commitFailure = new IllegalStateException("transaction is closed");
        CommitOperator operator = new CommitOperator(concurrencyControl, 42);

        operator.open();
        IResultRow result = operator.next();

        assertEquals(1, concurrencyControl.commitCalls);
        assertEquals("COMMIT rejected: transaction is closed", result.get("message"));
        assertNull(operator.next());
    }
}
