package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.Main;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.QueryBinder;
import mdbms.optimizer.cost.CardinalityEstimator;
import mdbms.optimizer.cost.SystemCostEstimator;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import org.junit.jupiter.api.Test;

// QO-12/QO-14: urutan join dipilih dari beberapa kandidat berdasarkan biaya total
class JoinOrderingRuleTest {
    private final JoinOrderingRule rule = new JoinOrderingRule();

    // big 10rb baris (500 blok), mid 100 baris (5 blok), tiny 10 baris (1 blok)
    private static Main.InMemorySchemaCatalog catalog() {
        Main.InMemorySchemaCatalog catalog = new Main.InMemorySchemaCatalog();
        catalog.addTable(table("big", "x", "p"), new Statistic("big", 10_000, 500, 20, 20, Map.of("x", 10_000, "p", 100)));
        catalog.addTable(table("mid", "y", "q"), new Statistic("mid", 100, 5, 20, 20, Map.of("y", 100, "q", 10)));
        catalog.addTable(table("tiny", "z", "r"), new Statistic("tiny", 10, 1, 20, 20, Map.of("z", 10, "r", 10)));
        return catalog;
    }

    private static TableSchema table(String name, String first, String second) {
        return new TableSchema(name, List.of(
            new ColumnDefinition(first, DataType.INTEGER, 4, false),
            new ColumnDefinition(second, DataType.INTEGER, 4, false)), List.of(), List.of());
    }

    private record Setup(QueryTree join, OptimizationContext context, SystemCostEstimator estimator) {
    }

    // ambil node di bawah PROJECT, belum dioptimasi
    private static Setup setup(String sql) {
        Main.InMemorySchemaCatalog catalog = catalog();
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog).bind(tree);
        SystemCostEstimator estimator = new SystemCostEstimator(catalog, new CardinalityEstimator());
        return new Setup(tree.getChildren().get(0), new OptimizationContext(bindings, catalog, catalog, estimator), estimator);
    }

    private static int cost(Setup setup, QueryTree tree) {
        return setup.estimator().calculateTotalCost(tree);
    }

    private static void collectJoinConditions(QueryTree node, List<String> out) {
        if (node.getNodeType() == QueryNodeType.JOIN) {
            node.getConditions().forEach(condition -> out.add(condition.getRoot().getValue()
                + condition.getRoot().getLeft().getValue() + condition.getRoot().getRight().getValue()));
        }
        node.getChildren().forEach(child -> collectJoinConditions(child, out));
    }

    @Test
    void neverReturnsAPlanMoreExpensiveThanTheWrittenOne() {
        // urutan tulisan ini sudah murah; greedy berbasis jumlah baris dulu bikin lebih mahal
        Setup setup = setup("SELECT big.p FROM big JOIN mid ON big.x = mid.y JOIN tiny ON mid.q = tiny.r");

        QueryTree result = rule.apply(setup.join(), setup.context());

        assertTrue(cost(setup, result) <= cost(setup, setup.join()),
            "reordered " + cost(setup, result) + " > written " + cost(setup, setup.join()));
    }

    @Test
    void choosesACheaperOrderWhenTheWrittenOneIsExpensive() {
        Setup setup = setup("SELECT big.p FROM tiny, big, mid WHERE big.x = mid.y AND mid.q = tiny.r");

        QueryTree result = rule.apply(setup.join(), setup.context());

        assertTrue(cost(setup, result) < cost(setup, setup.join()));
        List<String> conditions = new ArrayList<>();
        collectJoinConditions(result, conditions);
        assertEquals(2, conditions.size(), "both join predicates must survive reordering");
    }

    @Test
    void swapsSidesSoTheSmallerInputIsTheOuterLoop() {
        // BNLJ: big di luar 500 + 167 * 1, tiny di luar 1 + 1 * 500
        Setup setup = setup("SELECT big.p FROM big JOIN tiny ON big.x = tiny.z");

        QueryTree result = rule.apply(setup.join(), setup.context());

        assertEquals(QueryNodeType.JOIN, result.getNodeType());
        assertEquals("tiny", result.getChildren().get(0).getTableName());
        assertEquals("big", result.getChildren().get(1).getTableName());
    }

    @Test
    void applyingTwiceKeepsTheSamePlan() {
        Setup setup = setup("SELECT big.p FROM tiny, big, mid WHERE big.x = mid.y AND mid.q = tiny.r");

        QueryTree once = rule.apply(setup.join(), setup.context());

        assertSame(once, rule.apply(once, setup.context()));
    }

    @Test
    void keepsLocalFiltersOnTheirTable() {
        Setup setup = setup("SELECT big.p FROM big, mid WHERE big.x = mid.y AND big.p = 3");

        QueryTree result = rule.apply(setup.join(), setup.context());

        QueryTree bigSide = result.getChildren().stream()
            .filter(child -> child.getNodeType() == QueryNodeType.SELECT).findFirst().orElseThrow();
        assertEquals("big", bigSide.getChildren().get(0).getTableName());
    }

    @Test
    void endToEndOptimizationIsNoMoreExpensiveThanTheOriginal() {
        for (String sql : List.of(
            "SELECT big.p FROM big JOIN mid ON big.x = mid.y JOIN tiny ON mid.q = tiny.r",
            "SELECT big.p FROM tiny, big, mid WHERE big.x = mid.y AND mid.q = tiny.r",
            "SELECT * FROM big, tiny")) {
            Main.InMemorySchemaCatalog catalog = catalog();
            QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
            BindingContext bindings = new QueryBinder(catalog).bind(tree);
            SystemCostEstimator estimator = new SystemCostEstimator(catalog, new CardinalityEstimator());
            OptimizationContext context = new OptimizationContext(bindings, catalog, catalog, estimator);
            int before = estimator.calculateTotalCost(tree);

            QueryTree optimized = new RuleBasedOptimizer(RuleBasedOptimizer.defaultRules()).optimize(tree, context);

            assertTrue(estimator.calculateTotalCost(optimized) <= before, sql);
        }
    }
}
