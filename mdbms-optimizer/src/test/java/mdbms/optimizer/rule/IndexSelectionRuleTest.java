package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
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
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import org.junit.jupiter.api.Test;

class IndexSelectionRuleTest {
    private final IndexSelectionRule rule = new IndexSelectionRule();

    // katalog in-memory bawaan ga nyimpen index, jadi ditambahin di sini
    private static final class IndexedCatalog extends Main.InMemorySchemaCatalog {
        private final List<IndexInfo> indexes = new ArrayList<>();

        void addIndex(String name, String table, String column) {
            indexes.add(new IndexInfo(name, table, column, IndexType.BPLUS_TREE));
        }

        @Override
        public List<IndexInfo> getIndexes(String table) {
            return indexes.stream()
                .filter(index -> index.tableName().equalsIgnoreCase(table.toLowerCase(Locale.ROOT)))
                .toList();
        }
    }

    // students(id, name, gpa) index di id, takes(nim, code, grade) tanpa index
    private static IndexedCatalog catalog() {
        IndexedCatalog catalog = new IndexedCatalog();
        catalog.addTable(new TableSchema("students", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 4, false),
            new ColumnDefinition("name", DataType.VARCHAR, 50, false),
            new ColumnDefinition("gpa", DataType.FLOAT, 4, false)), List.of("id"), List.of()));
        catalog.addTable(new TableSchema("takes", List.of(
            new ColumnDefinition("nim", DataType.INTEGER, 4, false),
            new ColumnDefinition("code", DataType.VARCHAR, 10, false),
            new ColumnDefinition("grade", DataType.CHAR, 2, false)), List.of(), List.of()));
        catalog.addIndex("idx_students_id", "students", "id");
        return catalog;
    }

    private QueryTree optimize(String sql, IndexedCatalog catalog) {
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog).bind(tree);
        OptimizationContext context = new OptimizationContext(bindings, catalog, catalog, null);
        return rule.apply(findSelect(tree), context);
    }

    // pakai cost model asli + statistik students
    private QueryTree optimizeWithCost(String sql, int rows, int blocks, IndexedCatalog catalog) {
        catalog.addTable(catalog.getTableSchema("students"), new Statistic("students", rows, blocks, 40, 10,
            Map.of("id", rows, "name", 10, "gpa", 40)));
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog).bind(tree);
        OptimizationContext context = new OptimizationContext(bindings, catalog, catalog,
            new SystemCostEstimator(catalog, new CardinalityEstimator()));
        return rule.apply(findSelect(tree), context);
    }

    private QueryTree optimize(String sql) {
        return optimize(sql, catalog());
    }

    private static QueryTree findSelect(QueryTree node) {
        if (node.getNodeType() == QueryNodeType.SELECT) {
            return node;
        }
        for (QueryTree child : node.getChildren()) {
            QueryTree found = findSelect(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static QueryTree scan(String table) {
        return new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, table, List.of(), List.of(), 0);
    }

    @Test
    void matchesOnlySelectionsSittingOnATable() {
        QueryTree tree = new ShaylaParser(new ShaylaLexer(""))
            .parseToTree("SELECT * FROM students, takes WHERE students.id = takes.nim");

        assertTrue(rule.matches(findSelect(new ShaylaParser(new ShaylaLexer(""))
            .parseToTree("SELECT * FROM students WHERE id = 1"))));
        assertFalse(rule.matches(findSelect(tree)));
        assertFalse(rule.matches(scan("students")));
        assertFalse(rule.matches(null));
    }

    @Test
    void equalityOnIndexedColumnBecomesIndexScan() {
        QueryTree result = optimize("SELECT * FROM students WHERE id = 7");

        assertEquals(QueryNodeType.TABLE_SCAN, result.getNodeType());
        assertEquals(PhysicalAlgorithm.INDEX_SCAN, result.getAlgorithm());
        assertEquals("idx_students_id", result.getIndexScan().index().indexName());
        assertEquals(new Condition("id", ComparisonOperator.EQ, 7L), result.getIndexScan().condition());
        assertEquals(1, result.getConditions().size());
    }

    @Test
    void otherConditionsStayAboveTheIndexScan() {
        QueryTree result = optimize("SELECT * FROM students WHERE gpa > 3.0 AND id = 7 AND name = 'a'");

        assertEquals(QueryNodeType.SELECT, result.getNodeType());
        QueryTree next = result.getChildren().get(0);
        assertEquals(QueryNodeType.SELECT, next.getNodeType());
        QueryTree bottom = next.getChildren().get(0);
        assertEquals(QueryNodeType.TABLE_SCAN, bottom.getNodeType());
        assertEquals("id", bottom.getIndexScan().condition().column());
        assertEquals("gpa", columnOf(result));
        assertEquals("name", columnOf(next));
    }

    private static String columnOf(QueryTree select) {
        return QueryTreeRewrites.columnsIn(select.getConditions().get(0)).get(0).getColumnName();
    }

    @Test
    void rangeOnBPlusTreeUsesIndex() {
        QueryTree result = optimize("SELECT * FROM students WHERE id >= 10");

        assertEquals(new Condition("id", ComparisonOperator.GTE, 10L), result.getIndexScan().condition());
    }

    @Test
    void literalOnTheLeftIsFlipped() {
        QueryTree result = optimize("SELECT * FROM students WHERE 5 < id");

        assertEquals(new Condition("id", ComparisonOperator.GT, 5L), result.getIndexScan().condition());
    }

    @Test
    void notEqualNeverUsesIndex() {
        QueryTree select = findSelect(new ShaylaParser(new ShaylaLexer(""))
            .parseToTree("SELECT * FROM students WHERE id <> 7"));
        IndexedCatalog catalog = catalog();
        OptimizationContext context = new OptimizationContext(
            new QueryBinder(catalog).bind(select), catalog, catalog, null);

        assertSame(select, rule.apply(select, context));
    }

    @Test
    void columnWithoutIndexIsLeftAlone() {
        QueryTree result = optimize("SELECT * FROM students WHERE name = 'a'");

        assertEquals(QueryNodeType.SELECT, result.getNodeType());
        assertNull(result.getChildren().get(0).getIndexScan());
    }

    @Test
    void tableWithoutIndexIsLeftAlone() {
        QueryTree result = optimize("SELECT * FROM takes WHERE nim = 7");

        assertEquals(QueryNodeType.SELECT, result.getNodeType());
        assertNull(result.getChildren().get(0).getIndexScan());
    }

    @Test
    void comparisonBetweenColumnsOrArithmeticIsSkipped() {
        assertEquals(QueryNodeType.SELECT, optimize("SELECT * FROM students WHERE id = gpa").getNodeType());
        assertEquals(QueryNodeType.SELECT, optimize("SELECT * FROM students WHERE id + 1 = 7").getNodeType());
    }

    @Test
    void unqualifiedAndQualifiedColumnsBothWork() {
        assertEquals("id", optimize("SELECT * FROM students WHERE students.id = 7")
            .getIndexScan().condition().column());
        assertEquals("id", optimize("SELECT * FROM students WHERE id = 7")
            .getIndexScan().condition().column());
    }

    @Test
    void stringLiteralKeepsItsText() {
        IndexedCatalog catalog = catalog();
        catalog.addIndex("idx_students_name", "students", "name");

        QueryTree result = optimize("SELECT * FROM students WHERE name = 'budi'", catalog);

        assertEquals(new Condition("name", ComparisonOperator.EQ, "budi"), result.getIndexScan().condition());
    }

    @Test
    void withoutContextNothingChanges() {
        QueryTree select = findSelect(new ShaylaParser(new ShaylaLexer(""))
            .parseToTree("SELECT * FROM students WHERE id = 7"));

        assertSame(select, rule.apply(select, null));
    }

    @Test
    void scanThatAlreadyUsesAnIndexIsNotMatchedAgain() {
        QueryTree result = optimize("SELECT * FROM students WHERE id = 7 AND gpa > 3.0");

        assertEquals(QueryNodeType.SELECT, result.getNodeType());
        assertFalse(rule.matches(result));
    }

    @Test
    void indexBeatsFullScanOnLargeTable() {
        QueryTree result = optimizeWithCost("SELECT * FROM students WHERE id = 7", 10_000, 1_000, catalog());

        assertEquals(PhysicalAlgorithm.INDEX_SCAN, result.getAlgorithm());
    }

    @Test
    void tinyTableKeepsFullScan() {
        QueryTree result = optimizeWithCost("SELECT * FROM students WHERE id = 7", 10, 1, catalog());

        assertEquals(QueryNodeType.SELECT, result.getNodeType());
        assertNull(result.getChildren().get(0).getIndexScan());
    }

    @Test
    void picksTheCheapestIndexNotTheFirstOne() {
        IndexedCatalog catalog = catalog();
        catalog.addIndex("idx_students_name", "students", "name");

        QueryTree result = optimizeWithCost("SELECT * FROM students WHERE name = 'a' AND id = 7",
            10_000, 1_000, catalog);

        assertEquals("idx_students_id", bottomOf(result).getIndexScan().index().indexName());
        assertEquals("name", columnOf(result));
    }

    @Test
    void equalityPreferredOverRangeWithCost() {
        QueryTree result = optimizeWithCost("SELECT * FROM students WHERE id > 5 AND id = 7",
            10_000, 1_000, catalog());

        assertEquals(ComparisonOperator.EQ, bottomOf(result).getIndexScan().condition().operator());
    }

    @Test
    void equalityPreferredOverRangeWithoutStatistics() {
        QueryTree result = optimize("SELECT * FROM students WHERE id > 5 AND id = 7");

        assertEquals(ComparisonOperator.EQ, bottomOf(result).getIndexScan().condition().operator());
    }

    @Test
    void indexScanIsCheaperForTheCostModel() {
        IndexedCatalog catalog = catalog();
        QueryTree result = optimizeWithCost("SELECT * FROM students WHERE id = 7", 10_000, 1_000, catalog);
        SystemCostEstimator estimator = new SystemCostEstimator(catalog, new CardinalityEstimator());

        assertTrue(estimator.calculateTotalCost(result) < estimator.calculateTotalCost(
            new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.LINEAR_SCAN, "students",
                List.of(), List.of(), 0)));
    }

    private static QueryTree bottomOf(QueryTree node) {
        QueryTree current = node;
        while (!current.getChildren().isEmpty()) {
            current = current.getChildren().get(0);
        }
        return current;
    }
}
