package mdbms.optimizer.cost;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PlanEstimateTest {

    @Test
    void testConstructorAndGetters() {
        Map<String, Long> dv = Map.of("id", 100L, "name", 80L);
        PlanEstimate estimate = new PlanEstimate(100L, 10L, dv);

        assertEquals(100L, estimate.getEstimatedRows());
        assertEquals(10L, estimate.getEstimatedBlocks());
        assertEquals(100L, estimate.getDistinctValues("id"));
        assertEquals(80L, estimate.getDistinctValues("name"));
        assertEquals(0L, estimate.getDistinctValues("unknown"));
    }

    @Test
    void testNegativeValuesClampedToZero() {
        PlanEstimate estimate = new PlanEstimate(-10L, -5L, null);
        assertEquals(0L, estimate.getEstimatedRows());
        assertEquals(0L, estimate.getEstimatedBlocks());
        assertNotNull(estimate.getAllDistinctValues());
        assertTrue(estimate.getAllDistinctValues().isEmpty());
    }

    @Test
    void testGetDistinctValuesCaseInsensitiveAndQualified() {
        Map<String, Long> dv = Map.of("students.gpa", 50L, "id", 100L);
        PlanEstimate estimate = new PlanEstimate(100L, 10L, dv);

        // Case-insensitive
        assertEquals(100L, estimate.getDistinctValues("ID"));
        assertEquals(50L, estimate.getDistinctValues("STUDENTS.GPA"));

        // Looking up "gpa" when key is "students.gpa"
        assertEquals(50L, estimate.getDistinctValues("gpa"));

        // Looking up "students.id" when key is "id"
        assertEquals(100L, estimate.getDistinctValues("students.id"));
    }

    @Test
    void testEqualsAndHashCode() {
        PlanEstimate e1 = new PlanEstimate(50L, 5L, Map.of("a", 10L));
        PlanEstimate e2 = new PlanEstimate(50L, 5L, Map.of("a", 10L));
        PlanEstimate e3 = new PlanEstimate(60L, 6L, Map.of("a", 10L));

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
        assertNotEquals(e1, e3);
        assertNotNull(e1.toString());
    }
}

