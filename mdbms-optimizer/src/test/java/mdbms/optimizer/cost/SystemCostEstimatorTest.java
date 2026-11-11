package mdbms.optimizer.cost;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.IStatisticsProvider;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.Statistic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemCostEstimatorTest {
    private MockStatisticsProvider statsProvider;
    private CardinalityEstimator cardinalityEstimator;
    private SystemCostEstimator costEstimator;

    @BeforeEach
    void setUp() {
        statsProvider = new MockStatisticsProvider();
        // students: 1000 tuples, 50 blocks, record size 200, blocking factor 20
        statsProvider.addStatistics(new Statistic(
            "students",
            1000,
            50,
            200,
            20,
            Map.of("id", 1000, "gpa", 100, "dept", 10)
        ));

        // takes: 2000 tuples, 100 blocks, record size 200, blocking factor 20
        statsProvider.addStatistics(new Statistic(
            "takes",
            2000,
            100,
            200,
            20,
            Map.of("id", 800, "course", 50)
        ));

        cardinalityEstimator = new CardinalityEstimator();
        costEstimator = new SystemCostEstimator(statsProvider, cardinalityEstimator);
    }

    @Test
    void testCalculateNodeCostLinearScan() {
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "students", List.of(), List.of(), 0);
        int cost = costEstimator.calculateNodeCost(scan, PhysicalAlgorithm.LINEAR_SCAN, null, null);
        // Cost of linear scan = b_r = 50
        assertEquals(50, cost);
    }

    @Test
    void testCalculateNodeCostIndexScanCheaperThanLinearScan() {
        // High selectivity equality condition (e.g. id = 123)
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "id"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "123")
        );
        QueryTree scan = new QueryTree(
            QueryNodeType.TABLE_SCAN,
            PhysicalAlgorithm.INDEX_SCAN,
            "students",
            List.of(new ExpressionTree(eq)),
            List.of(),
            0
        );

        PlanEstimate est = costEstimator.estimateOutput(scan);
        int indexCost = costEstimator.calculateNodeCost(scan, PhysicalAlgorithm.INDEX_SCAN, est, null);

        // Index scan cost must be significantly lower than full table scan (50)
        assertTrue(indexCost < 50);
        assertTrue(indexCost >= 2); // At least tree height
    }

    @Test
    void testCalculateNodeCostBlockNestedLoopJoin() {
        PlanEstimate left = new PlanEstimate(100L, 10L, Map.of());
        PlanEstimate right = new PlanEstimate(200L, 20L, Map.of());
        QueryTree join = new QueryTree(QueryNodeType.JOIN, PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, null, List.of(), List.of(), 0);

        int cost = costEstimator.calculateNodeCost(join, PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, left, right);
        // BNLJ formula: B(R) + ceil(B(R) / 3) * B(S)
        // 10 + ceil(10 / 3) * 20 = 10 + 4 * 20 = 90
        assertEquals(90, cost);
    }

    @Test
    void testCalculateNodeCostOtherJoinAlgorithms() {
        PlanEstimate left = new PlanEstimate(100L, 10L, Map.of());
        PlanEstimate right = new PlanEstimate(200L, 20L, Map.of());
        QueryTree join = new QueryTree(QueryNodeType.JOIN, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(), 0);

        // Nested Loop Join: B(R) + N(R) * B(S) = 10 + 100 * 20 = 2010
        int nljCost = costEstimator.calculateNodeCost(join, PhysicalAlgorithm.NESTED_LOOP_JOIN, left, right);
        assertEquals(2010, nljCost);

        // Hash Join: 3 * (B(R) + B(S)) = 3 * (10 + 20) = 90
        int hashCost = costEstimator.calculateNodeCost(join, PhysicalAlgorithm.HASH_JOIN, left, right);
        assertEquals(90, hashCost);

        // Merge Join: B(R) + B(S) = 10 + 20 = 30
        int mergeCost = costEstimator.calculateNodeCost(join, PhysicalAlgorithm.MERGE_JOIN, left, right);
        assertEquals(30, mergeCost);
    }

    @Test
    void testEstimateOutputFromStatistics() {
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        PlanEstimate output = costEstimator.estimateOutput(scan);

        assertEquals(1000L, output.getEstimatedRows());
        assertEquals(50L, output.getEstimatedBlocks());
        assertEquals(1000L, output.getDistinctValues("id"));
        assertEquals(10L, output.getDistinctValues("dept"));
    }

    @Test
    void testCalculateTotalCostSingleTable() {
        // SELECT * FROM students
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "students", List.of(), List.of(), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(scan),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        int totalCost = costEstimator.calculateTotalCost(project);
        // scan cost is 50, project cost is 0 -> total is 50
        assertEquals(50, totalCost);
        assertEquals(50, scan.getEstimatedCost());
        assertEquals(0, project.getEstimatedCost());
    }

    @Test
    void testCalculateTotalCostMultiNodePlan() {
        // students JOIN takes using BNLJ
        QueryTree scan1 = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "students", List.of(), List.of(), 0);
        QueryTree scan2 = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "takes", List.of(), List.of(), 0);
        QueryTree join = new QueryTree(QueryNodeType.JOIN, PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, null, List.of(), List.of(scan1, scan2), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(join),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        int totalCost = costEstimator.calculateTotalCost(project);
        // scan1: 50
        // scan2: 100
        // join BNLJ: B(students) + ceil(50/3) * B(takes) = 50 + 17 * 100 = 1750
        // project: 0
        // total: 50 + 100 + 1750 = 1900
        assertEquals(1900, totalCost);
    }

    @Test
    void testPushdownSelectionYieldsLowerOrEqualCost() {
        // Specification requirement QO-08:
        // "Plan yang menghasilkan hasil antara lebih kecil tidak diberi biaya lebih tinggi
        // dibanding plan setara dengan hasil antara lebih besar"

        // Plan 1 (Without pushdown):
        // SELECT * FROM students JOIN takes ON students.id = takes.id WHERE students.dept = 'CS'
        // Filter is at top
        QueryTree scan1A = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "students", List.of(), List.of(), 0);
        QueryTree scan2A = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "takes", List.of(), List.of(), 0);
        ExpressionNode joinEq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column("students", "id"),
            ExpressionNode.column("takes", "id")
        );
        QueryTree joinA = new QueryTree(
            QueryNodeType.JOIN,
            PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN,
            null,
            List.of(new ExpressionTree(joinEq)),
            List.of(scan1A, scan2A),
            0
        );
        ExpressionNode filterEq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "dept"),
            ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "CS")
        );
        QueryTree filterA = new QueryTree(
            QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(new ExpressionTree(filterEq)),
            List.of(joinA),
            0
        );
        QueryTree projectA = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(filterA),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        int costPlan1 = costEstimator.calculateTotalCost(projectA);

        // Plan 2 (With pushdown):
        // Filter 'students.dept = CS' pushed down right above students
        QueryTree scan1B = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "students", List.of(), List.of(), 0);
        QueryTree filterB = new QueryTree(
            QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(new ExpressionTree(filterEq)),
            List.of(scan1B),
            0
        );
        QueryTree scan2B = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "takes", List.of(), List.of(), 0);
        QueryTree joinB = new QueryTree(
            QueryNodeType.JOIN,
            PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN,
            null,
            List.of(new ExpressionTree(joinEq)),
            List.of(filterB, scan2B),
            0
        );
        QueryTree projectB = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(joinB),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        int costPlan2 = costEstimator.calculateTotalCost(projectB);

        // Pushed-down plan MUST be strictly cheaper than non-pushed-down plan
        assertTrue(costPlan2 < costPlan1, "Expected pushed-down plan cost (" + costPlan2 + ") to be strictly less than non-pushed-down cost (" + costPlan1 + ")");
    }

    private static class MockStatisticsProvider implements IStatisticsProvider {
        private final Map<String, Statistic> stats = new HashMap<>();

        void addStatistics(Statistic statistic) {
            stats.put(statistic.tableName().toLowerCase(), statistic);
        }

        @Override
        public Statistic getStatistics(String table) {
            return table == null ? null : stats.get(table.toLowerCase());
        }
    }
}

