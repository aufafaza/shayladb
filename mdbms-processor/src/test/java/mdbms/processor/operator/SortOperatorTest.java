package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import mdbms.processor.utility.IntegerRowUtility;

class SortOperatorTest {
    @Test
    void sortAscending() {
        assertEquals(List.of(2, 5, 9), IntegerRowUtility.collect(
                new SortOperator(IntegerRowUtility.source(9, 2, 5),
                        "value", true)));
    }

    @Test
    void sortDescending() {
        assertEquals(List.of(9, 5, 2), IntegerRowUtility.collect(
                new SortOperator(IntegerRowUtility.source(9, 2, 5),
                        "value", false)));
    }

    @Test
    void sortThenLimit() {
        assertEquals(List.of(2, 5), IntegerRowUtility.collect(new LimitOperator(
                new SortOperator(IntegerRowUtility.source(9, 2, 5),
                        "value", true),
                2)));
    }

    @Test
    void sortMultipleColumns() {
        IQueryOperator input = IntegerRowUtility.source(3, 1, 2);

        SortOperator operator = new SortOperator(input, List.of(
                new SortOperator.SortKey(null, "kelompok", true),
                new SortOperator.SortKey(null, "value", false)));

        assertEquals(List.of(2, 3, 1), IntegerRowUtility.collect(operator));
    }

    @Test
    void sortMultipleColumnsAscending() {
        IQueryOperator input = IntegerRowUtility.source(9, 2, 5);

        SortOperator operator = new SortOperator(input, List.of(
                new SortOperator.SortKey(null, "kelompok", true),
                new SortOperator.SortKey(null, "value", true)));

        assertEquals(List.of(2, 5, 9), IntegerRowUtility.collect(operator));
    }
}
