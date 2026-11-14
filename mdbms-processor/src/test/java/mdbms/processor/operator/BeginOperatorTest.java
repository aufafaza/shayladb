package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BeginOperatorTest {

    @Test
    void returnsBeginMessage() {
        BeginOperator operator = new BeginOperator(42);

        operator.open();
        assertNull(operator.next());
        assertEquals("BEGIN TRANSACTION 42", operator.getMessage());
        assertNull(operator.next());
    }
}
