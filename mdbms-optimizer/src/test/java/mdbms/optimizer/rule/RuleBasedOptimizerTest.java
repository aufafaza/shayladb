package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import org.junit.jupiter.api.Test;

class RuleBasedOptimizerTest {
    // rule palsu buat ngecek urutan dan jumlah pemanggilan
    private static final class FakeRule implements IOptimizationRule {
        private final String name;
        private final QueryNodeType type;
        private final Function<QueryTree, QueryTree> action;
        private final List<String> log;

        FakeRule(String name, QueryNodeType type, Function<QueryTree, QueryTree> action, List<String> log) {
            this.name = name;
            this.type = type;
            this.action = action;
            this.log = log;
        }

        @Override
        public String getRuleName() {
            return name;
        }

        @Override
        public boolean matches(QueryTree node) {
            return node != null && node.getNodeType() == type;
        }

        @Override
        public QueryTree apply(QueryTree node, OptimizationContext context) {
            log.add(name + ":" + node.getNodeType() + (node.getTableName() == null ? "" : ":" + node.getTableName()));
            return action.apply(node);
        }
    }

    private static QueryTree scan(String table) {
        return new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, table, List.of(), List.of(), 0);
    }

    private static QueryTree cartesian(QueryTree left, QueryTree right) {
        return new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(left, right), 0);
    }

    @Test
    void nullRootStaysNull() {
        assertNull(new RuleBasedOptimizer(List.of()).optimize(null, null));
    }

    @Test
    void withoutRulesTheSameTreeComesBack() {
        QueryTree tree = cartesian(scan("a"), scan("b"));

        assertSame(tree, new RuleBasedOptimizer(List.of()).optimize(tree, null));
        assertSame(tree, new RuleBasedOptimizer(null).optimize(tree, null));
    }

    @Test
    void childrenAreVisitedBeforeParents() {
        List<String> log = new ArrayList<>();
        RuleBasedOptimizer optimizer = new RuleBasedOptimizer(List.of(
            new FakeRule("scan", QueryNodeType.TABLE_SCAN, node -> node, log),
            new FakeRule("join", QueryNodeType.CARTESIAN, node -> node, log)));

        optimizer.optimize(cartesian(scan("a"), scan("b")), null);

        assertEquals(List.of("scan:TABLE_SCAN:a", "scan:TABLE_SCAN:b", "join:CARTESIAN"), log);
    }

    @Test
    void rulesRunInListOrderOnTheSameNode() {
        List<String> log = new ArrayList<>();
        RuleBasedOptimizer optimizer = new RuleBasedOptimizer(List.of(
            new FakeRule("first", QueryNodeType.TABLE_SCAN, node -> node, log),
            new FakeRule("second", QueryNodeType.TABLE_SCAN, node -> node, log)));

        optimizer.optimize(scan("a"), null);

        assertEquals(List.of("first:TABLE_SCAN:a", "second:TABLE_SCAN:a"), log);
    }

    @Test
    void laterRuleSeesTheResultOfTheEarlierOne() {
        List<String> log = new ArrayList<>();
        RuleBasedOptimizer optimizer = new RuleBasedOptimizer(List.of(
            new FakeRule("rename", QueryNodeType.TABLE_SCAN,
                node -> "a".equals(node.getTableName()) ? scan("b") : node, log),
            new FakeRule("check", QueryNodeType.TABLE_SCAN, node -> node, log)));

        QueryTree result = optimizer.optimize(scan("a"), null);

        assertEquals("b", result.getTableName());
        assertEquals("check:TABLE_SCAN:b", log.get(1));
    }

    @Test
    void ruleReturningNullIsIgnored() {
        List<String> log = new ArrayList<>();
        QueryTree tree = scan("a");
        RuleBasedOptimizer optimizer = new RuleBasedOptimizer(List.of(
            new FakeRule("stub", QueryNodeType.TABLE_SCAN, node -> null, log)));

        assertSame(tree, optimizer.optimize(tree, null));
    }

    @Test
    void stopsOnceTheTreeNoLongerChanges() {
        List<String> log = new ArrayList<>();
        RuleBasedOptimizer optimizer = new RuleBasedOptimizer(List.of(
            new FakeRule("rename", QueryNodeType.TABLE_SCAN,
                node -> "a".equals(node.getTableName()) ? scan("b") : node, log)));

        optimizer.optimize(scan("a"), null);

        // putaran 1 ngubah, putaran 2 ngecek udah stabil
        assertEquals(2, log.size());
    }

    @Test
    void flipFloppingRuleIsCappedAtMaxPasses() {
        List<String> log = new ArrayList<>();
        RuleBasedOptimizer optimizer = new RuleBasedOptimizer(List.of(
            new FakeRule("flip", QueryNodeType.TABLE_SCAN,
                node -> scan("a".equals(node.getTableName()) ? "b" : "a"), log)));

        optimizer.optimize(scan("a"), null);

        assertEquals(RuleBasedOptimizer.MAX_PASSES, log.size());
    }

    @Test
    void addRuleAppendsEvenWhenBuiltFromImmutableList() {
        List<String> log = new ArrayList<>();
        RuleBasedOptimizer optimizer = new RuleBasedOptimizer(List.of());
        IOptimizationRule rule = new FakeRule("late", QueryNodeType.TABLE_SCAN, node -> node, log);

        optimizer.addRule(rule);
        optimizer.optimize(scan("a"), null);

        assertEquals(List.of(rule), optimizer.getRules());
        assertEquals(List.of("late:TABLE_SCAN:a"), log);
        assertThrows(NullPointerException.class, () -> optimizer.addRule(null));
        assertThrows(UnsupportedOperationException.class, () -> optimizer.getRules().clear());
    }

    @Test
    void defaultRulesKeepPushdownBeforeIndexAndProjectionLast() {
        List<IOptimizationRule> rules = RuleBasedOptimizer.defaultRules();
        List<Class<?>> types = rules.stream().<Class<?>>map(Object::getClass).toList();

        assertEquals(7, rules.size());
        assertEquals(true, types.indexOf(SelectionPushDownRule.class) < types.indexOf(IndexSelectionRule.class));
        assertEquals(ProjectionPushDownRule.class, types.get(types.size() - 1));
    }
}
