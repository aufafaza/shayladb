package mdbms.processor.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

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
        // group = value % 3, jadi 1 dan 4 sama-sama group 1 dan diurutkan value DESC
        IQueryOperator input = IntegerRowUtility.source(1, 4, 2);

        SortOperator operator = new SortOperator(input, List.of(
                new SortOperator.SortKey(null, "group", true),
                new SortOperator.SortKey(null, "value", false)));

        assertEquals(List.of(4, 1, 2), IntegerRowUtility.collect(operator));
    }

    @Test
    void sortMultipleColumnsAscending() {
        IQueryOperator input = IntegerRowUtility.source(1, 2, 3, 4, 5, 6, 7, 8, 9);

        SortOperator operator = new SortOperator(input, List.of(
                new SortOperator.SortKey(null, "group", true),
                new SortOperator.SortKey(null, "value", true)));

        assertEquals(List.of(3, 6, 9, 1, 4, 7, 2, 5, 8), IntegerRowUtility.collect(operator));
    }
}
