package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.Main;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.QueryBinder;
import mdbms.optimizer.cost.CardinalityEstimator;
import mdbms.optimizer.cost.SystemCostEstimator;
import mdbms.optimizer.parser.ShaylaParser;
import org.junit.jupiter.api.Test;

// QO-12: algoritma join dipilih dari daftar algoritma QP berdasarkan biaya
class JoinAlgorithmSelectionRuleTest {
    private static final List<PhysicalAlgorithm> ALL = List.of(
        PhysicalAlgorithm.NESTED_LOOP_JOIN, PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN,
        PhysicalAlgorithm.INDEX_NESTED_LOOP_JOIN, PhysicalAlgorithm.HASH_JOIN, PhysicalAlgorithm.MERGE_JOIN);

    private static final class IndexedCatalog extends Main.InMemorySchemaCatalog {
        private final List<IndexInfo> indexes = new ArrayList<>();

        @Override
        public List<IndexInfo> getIndexes(String table) {
            return indexes.stream().filter(index -> index.tableName().equalsIgnoreCase(table)).toList();
        }
    }

    // l dan r sama-sama 500 blok, s cuma 5 blok; index di r.y
    private static IndexedCatalog catalog() {
        IndexedCatalog catalog = new IndexedCatalog();
        catalog.addTable(table("l", "x"), new Statistic("l", 10_000, 500, 20, 20, Map.of("x", 10_000)));
        catalog.addTable(table("r", "y"), new Statistic("r", 10_000, 500, 20, 20, Map.of("y", 10_000)));
        catalog.addTable(table("s", "z"), new Statistic("s", 100, 5, 20, 20, Map.of("z", 100)));
        catalog.indexes.add(new IndexInfo("idx_r_y", "r", "y", IndexType.BPLUS_TREE));
        return catalog;
    }

    private static TableSchema table(String name, String column) {
        return new TableSchema(name, List.of(new ColumnDefinition(column, DataType.INTEGER, 4, false)),
            List.of(), List.of());
    }

    private record Setup(QueryTree join, OptimizationContext context) {
    }

    private static Setup setup(String sql) {
        IndexedCatalog catalog = catalog();
        QueryTree tree = new ShaylaParser().parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog).bind(tree);
        SystemCostEstimator estimator = new SystemCostEstimator(catalog, new CardinalityEstimator());
        return new Setup(tree.getChildren().get(0), new OptimizationContext(bindings, catalog, catalog, estimator));
    }

    @Test
    void defaultStampsTheOnlyAlgorithmQueryProcessorProvides() {
        Setup setup = setup("SELECT * FROM l JOIN s ON l.x = s.z");

        QueryTree result = new JoinAlgorithmSelectionRule().apply(setup.join(), setup.context());

        assertEquals(PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, result.getAlgorithm());
        assertEquals(PhysicalAlgorithm.DEFAULT, setup.join().getAlgorithm(), "input node must not be mutated");
    }

    @Test
    void picksTheCheapestApplicableAlgorithm() {
        // BNLJ 500 + 167 * 500 jauh lebih mahal dari hash 3 * (500 + 500) atau merge 500 + 500
        Setup setup = setup("SELECT * FROM l JOIN r ON l.x = r.y");
        JoinAlgorithmSelectionRule rule = new JoinAlgorithmSelectionRule(List.of(
            PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, PhysicalAlgorithm.HASH_JOIN, PhysicalAlgorithm.MERGE_JOIN));

        assertEquals(PhysicalAlgorithm.MERGE_JOIN, rule.apply(setup.join(), setup.context()).getAlgorithm());
    }

    @Test
    void equiJoinAlgorithmsAreSkippedForNonEqualityConditions() {
        Setup setup = setup("SELECT * FROM l JOIN r ON l.x < r.y");
        JoinAlgorithmSelectionRule rule = new JoinAlgorithmSelectionRule(List.of(
            PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, PhysicalAlgorithm.HASH_JOIN, PhysicalAlgorithm.MERGE_JOIN));

        assertEquals(PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, rule.apply(setup.join(), setup.context()).getAlgorithm());
    }

    @Test
    void indexNestedLoopNeedsAnIndexOnTheInnerJoinColumn() {
        Setup indexed = setup("SELECT * FROM s JOIN r ON s.z = r.y");
        Setup unindexed = setup("SELECT * FROM r JOIN s ON r.y = s.z");

        assertEquals(true, JoinAlgorithmSelectionRule.applicable(
            PhysicalAlgorithm.INDEX_NESTED_LOOP_JOIN, indexed.join(), indexed.context()));
        assertFalse(JoinAlgorithmSelectionRule.applicable(
            PhysicalAlgorithm.INDEX_NESTED_LOOP_JOIN, unindexed.join(), unindexed.context()));
    }

    @Test
    void everyCandidateIsCostedWithTheSameEstimator() {
        Setup setup = setup("SELECT * FROM s JOIN r ON s.z = r.y");
        SystemCostEstimator estimator = (SystemCostEstimator) setup.context().getCostEstimator();
        var left = estimator.estimateOutput(setup.join().getChildren().get(0));
        var right = estimator.estimateOutput(setup.join().getChildren().get(1));
        PhysicalAlgorithm cheapest = null;
        int best = Integer.MAX_VALUE;
        for (PhysicalAlgorithm algorithm : ALL) {
            int cost = estimator.calculateNodeCost(setup.join(), algorithm, left, right);
            if (cost < best) {
                best = cost;
                cheapest = algorithm;
            }
        }

        assertEquals(cheapest, new JoinAlgorithmSelectionRule(ALL).apply(setup.join(), setup.context()).getAlgorithm());
    }

    @Test
    void joinsThatAlreadyHaveAnAlgorithmAreLeftAlone() {
        Setup setup = setup("SELECT * FROM l JOIN s ON l.x = s.z");
        JoinAlgorithmSelectionRule rule = new JoinAlgorithmSelectionRule();
        QueryTree once = rule.apply(setup.join(), setup.context());

        assertFalse(rule.matches(once));
        assertSame(once, rule.apply(once, setup.context()));
    }

    @Test
    void withoutAnEstimatorTheFirstApplicableAlgorithmIsUsed() {
        Setup setup = setup("SELECT * FROM l JOIN r ON l.x < r.y");
        JoinAlgorithmSelectionRule rule = new JoinAlgorithmSelectionRule(List.of(
            PhysicalAlgorithm.HASH_JOIN, PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN));
        OptimizationContext noEstimator = new OptimizationContext(
            setup.context().getBindingContext(), null, null, null);

        assertEquals(PhysicalAlgorithm.BLOCK_NESTED_LOOP_JOIN, rule.apply(setup.join(), noEstimator).getAlgorithm());
    }

    @Test
    void onlyThetaJoinsMatchAndAnEmptyAlgorithmListIsRejected() {
        Setup setup = setup("SELECT * FROM l, s");

        assertEquals(QueryNodeType.CARTESIAN, setup.join().getNodeType());
        assertFalse(new JoinAlgorithmSelectionRule().matches(setup.join()));
        assertThrows(IllegalArgumentException.class, () -> new JoinAlgorithmSelectionRule(List.of()));
    }
}
