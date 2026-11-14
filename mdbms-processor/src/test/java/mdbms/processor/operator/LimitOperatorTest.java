package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class LimitOperatorTest {
    @Test
    void limitReturnsRequestedRows() {
        assertEquals(List.of(9, 2), IntegerRowUtility.collect(new LimitOperator(IntegerRowUtility.source(9, 2, 5), 2)));
    }

    @Test
    void limitZeroReturnsNoRows() {
        assertEquals(List.of(), IntegerRowUtility.collect(new LimitOperator(IntegerRowUtility.source(9, 2), 0)));
    }

    @Test
    void negativeLimitRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LimitOperator(IntegerRowUtility.source(1), -1));
    }
}
