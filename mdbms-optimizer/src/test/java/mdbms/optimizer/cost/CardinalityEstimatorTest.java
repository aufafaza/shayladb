package mdbms.optimizer.cost;

import java.util.Map;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CardinalityEstimatorTest {
    private CardinalityEstimator estimator;
    private PlanEstimate studentsEstimate;
    private PlanEstimate takesEstimate;

    @BeforeEach
    void setUp() {
        estimator = new CardinalityEstimator();
        // students: 1000 rows, 50 blocks, V(id)=1000, V(dept)=10, V(gpa)=100
        studentsEstimate = new PlanEstimate(
            1000L,
            50L,
            Map.of("id", 1000L, "dept", 10L, "gpa", 100L)
        );

        // takes: 2000 rows, 100 blocks, V(id)=800, V(course)=50
        takesEstimate = new PlanEstimate(
            2000L,
            100L,
            Map.of("id", 800L, "course", 50L)
        );
    }

    @Test
    void testEstimateSelectionEqualityOnKey() {
        // WHERE id = 123 -> s = 1 / V(id) = 1 / 1000
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "id"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "123")
        );
        ExpressionTree tree = new ExpressionTree(eq);

        PlanEstimate result = estimator.estimateSelection(studentsEstimate, tree);
        assertEquals(1L, result.getEstimatedRows());
        assertEquals(1L, result.getEstimatedBlocks());
        assertEquals(1L, result.getDistinctValues("id"));
    }

    @Test
    void testEstimateSelectionEqualityNonKey() {
        // WHERE dept = 'CS' -> s = 1 / V(dept) = 1 / 10 -> 1000 * 0.1 = 100 rows
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "dept"),
            ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "CS")
        );
        ExpressionTree tree = new ExpressionTree(eq);

        PlanEstimate result = estimator.estimateSelection(studentsEstimate, tree);
        assertEquals(100L, result.getEstimatedRows());
        assertEquals(5L, result.getEstimatedBlocks()); // 50 * 0.1 = 5
        assertEquals(1L, result.getDistinctValues("dept"));
    }

    @Test
    void testEstimateSelectionRangeCondition() {
        // WHERE gpa > 3.5 -> heuristic range selectivity: 1/3 (approx 333 rows)
        ExpressionNode gt = ExpressionNode.comparison(
            ">",
            ExpressionNode.column(null, "gpa"),
            ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.5")
        );
        ExpressionTree tree = new ExpressionTree(gt);

        PlanEstimate result = estimator.estimateSelection(studentsEstimate, tree);
        assertEquals(333L, result.getEstimatedRows());
        assertEquals(17L, result.getEstimatedBlocks());
    }

    @Test
    void testEstimateSelectionConjunction() {
        // WHERE dept = 'CS' AND gpa > 3.5 -> 1000 * (1/10) * (1/3) = 33 rows
        ExpressionNode cond1 = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "dept"),
            ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "CS")
        );
        ExpressionNode cond2 = ExpressionNode.comparison(
            ">",
            ExpressionNode.column(null, "gpa"),
            ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.5")
        );
        ExpressionNode andCond = ExpressionNode.logicalAnd(cond1, cond2);
        ExpressionTree tree = new ExpressionTree(andCond);

        PlanEstimate result = estimator.estimateSelection(studentsEstimate, tree);
        assertEquals(33L, result.getEstimatedRows());
        assertEquals(2L, result.getEstimatedBlocks());
    }

    @Test
    void testEstimateCartesianProduct() {
        // students x takes -> 1000 * 2000 = 2,000,000 rows
        PlanEstimate result = estimator.estimateCartesianProduct(studentsEstimate, takesEstimate);
        assertEquals(2_000_000L, result.getEstimatedRows());
        assertTrue(result.getEstimatedBlocks() > 0);
        assertEquals(1000L, result.getDistinctValues("id"));
        assertEquals(10L, result.getDistinctValues("dept"));
        assertEquals(50L, result.getDistinctValues("course"));
    }

    @Test
    void testEstimateJoinEquality() {
        // students JOIN takes ON students.id = takes.id
        // V(students.id) = 1000, V(takes.id) = 800 -> max(1000, 800) = 1000
        // n_join = (1000 * 2000) / 1000 = 2000 rows
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column("students", "id"),
            ExpressionNode.column("takes", "id")
        );
        ExpressionTree tree = new ExpressionTree(eq);

        PlanEstimate result = estimator.estimateJoin(studentsEstimate, takesEstimate, tree);
        assertEquals(2000L, result.getEstimatedRows());
        assertTrue(result.getEstimatedBlocks() > 0);
    }

    @Test
    void testEstimateNullAndEmptyInputs() {
        PlanEstimate empty = estimator.estimateSelection(null, null);
        assertEquals(0L, empty.getEstimatedRows());
        assertEquals(0L, empty.getEstimatedBlocks());

        PlanEstimate cpNull = estimator.estimateCartesianProduct(null, studentsEstimate);
        assertEquals(0L, cpNull.getEstimatedRows());

        PlanEstimate joinNull = estimator.estimateJoin(studentsEstimate, null, null);
        assertEquals(0L, joinNull.getEstimatedRows());
    }
}
