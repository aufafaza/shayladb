package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.Main;
import mdbms.optimizer.QueryPlanPrinter;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.QueryBinder;
import mdbms.optimizer.cost.CardinalityEstimator;
import mdbms.optimizer.cost.SystemCostEstimator;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import org.junit.jupiter.api.Test;

// QO-09, QO-10, QO-13 dijalanin bareng lewat RuleBasedOptimizer
class RuleBasedOptimizerIntegrationTest {
    private static final class IndexedCatalog extends Main.InMemorySchemaCatalog {
        private final List<IndexInfo> indexes = new ArrayList<>();

        @Override
        public List<IndexInfo> getIndexes(String table) {
            return indexes.stream().filter(index -> index.tableName().equalsIgnoreCase(table)).toList();
        }
    }

    // students 10rb baris index di id, takes 50rb baris tanpa index
    private static IndexedCatalog catalog() {
        IndexedCatalog catalog = new IndexedCatalog();
        catalog.addTable(new TableSchema("students", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 4, false),
            new ColumnDefinition("name", DataType.VARCHAR, 50, false),
            new ColumnDefinition("gpa", DataType.FLOAT, 4, false)), List.of("id"), List.of()),
            new Statistic("students", 10_000, 1_000, 60, 10, Map.of("id", 10_000, "name", 9_000, "gpa", 40)));
        catalog.addTable(new TableSchema("takes", List.of(
            new ColumnDefinition("nim", DataType.INTEGER, 4, false),
            new ColumnDefinition("code", DataType.VARCHAR, 10, false),
            new ColumnDefinition("grade", DataType.CHAR, 2, false)), List.of(), List.of()),
            new Statistic("takes", 50_000, 2_500, 20, 20, Map.of("nim", 10_000, "code", 200, "grade", 5)));
        catalog.indexes.add(new IndexInfo("idx_students_id", "students", "id", IndexType.BPLUS_TREE));
        return catalog;
    }

    private record Run(QueryTree original, QueryTree optimized, OptimizationContext context,
                       SystemCostEstimator estimator) {
    }

    private static Run run(String sql) {
        IndexedCatalog catalog = catalog();
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog).bind(tree);
        SystemCostEstimator estimator = new SystemCostEstimator(catalog, new CardinalityEstimator());
        OptimizationContext context = new OptimizationContext(bindings, catalog, catalog, estimator);
        QueryTree optimized = new RuleBasedOptimizer(RuleBasedOptimizer.defaultRules()).optimize(tree, context);
        return new Run(tree, optimized, context, estimator);
    }

    private static String print(QueryTree tree) {
        return new QueryPlanPrinter().printPlan(tree);
    }

    @Test
    void joinQueryGetsPushdownIndexAndProjection() {
        Run run = run("SELECT students.name, takes.code FROM students, takes "
            + "WHERE students.id = takes.nim AND students.id = 7 AND takes.grade = 'A'");

        assertEquals("""
            PROJECT [columns=[students.name, takes.code]]
              SELECT [conditions=[(students.id = takes.nim)]]
                CARTESIAN
                  PROJECT [columns=[students.id, students.name]]
                    TABLE_SCAN [table=students, conditions=[(students.id = 7)], indexScan=idx_students_id id EQ 7, algorithm=INDEX_SCAN]
                  PROJECT [columns=[takes.nim, takes.code]]
                    SELECT [conditions=[(takes.grade = 'A')]]
                      TABLE_SCAN [table=takes]
            """, print(run.optimized()));
    }

    @Test
    void sortAndLimitStayOnTopOfIndexScan() {
        Run run = run("SELECT name FROM students WHERE gpa > 3.0 AND id >= 100 ORDER BY name LIMIT 5");

        assertEquals("""
            PROJECT [columns=[name]]
              LIMIT [limit=5]
                SORT [orderBy=[name ASC]]
                  SELECT [conditions=[(gpa > 3.0)]]
                    TABLE_SCAN [table=students, conditions=[(id >= 100)], indexScan=idx_students_id id GTE 100, algorithm=INDEX_SCAN]
            """, print(run.optimized()));
    }

    @Test
    void whereOnExplicitJoinMovesIntoIndexScan() {
        Run run = run("SELECT * FROM students JOIN takes ON students.id = takes.nim WHERE students.id = 7");

        assertEquals("""
            PROJECT [columns=[students.id, students.name, students.gpa, takes.nim, takes.code, takes.grade]]
              JOIN [conditions=[(students.id = takes.nim)]]
                TABLE_SCAN [table=students, conditions=[(students.id = 7)], indexScan=idx_students_id id EQ 7, algorithm=INDEX_SCAN]
                TABLE_SCAN [table=takes]
            """, print(run.optimized()));
    }

    @Test
    void optimizedPlanIsCheaper() {
        Run run = run("SELECT students.name, takes.code FROM students, takes "
            + "WHERE students.id = takes.nim AND students.id = 7 AND takes.grade = 'A'");

        assertTrue(run.estimator().calculateTotalCost(run.optimized())
            < run.estimator().calculateTotalCost(run.original()));
    }

    @Test
    void optimizingTwiceGivesTheSamePlan() {
        for (String sql : List.of(
            "SELECT students.name, takes.code FROM students, takes WHERE students.id = takes.nim AND students.id = 7",
            "SELECT name FROM students WHERE gpa > 3.0 AND id >= 100 ORDER BY name LIMIT 5",
            "SELECT * FROM students NATURAL JOIN takes WHERE grade = 'A'")) {
            Run run = run(sql);

            QueryTree again = new RuleBasedOptimizer(RuleBasedOptimizer.defaultRules())
                .optimize(run.optimized(), run.context());

            assertEquals(print(run.optimized()), print(again), sql);
        }
    }

    @Test
    void queryWithoutWhereKeepsItsShape() {
        Run run = run("SELECT * FROM students");

        assertEquals(print(run.original()), print(run.optimized()));
    }

    @Test
    void originalTreeIsLeftUntouched() {
        String sql = "SELECT students.name FROM students, takes WHERE students.id = takes.nim AND students.id = 7";
        QueryTree fresh = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        new QueryBinder(catalog()).bind(fresh);

        Run run = run(sql);

        assertEquals(print(fresh), print(run.original()));
    }
}
