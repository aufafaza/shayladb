package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.Main;
import mdbms.optimizer.QueryPlanPrinter;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.QueryBinder;
import mdbms.optimizer.binder.TableBinding;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import org.junit.jupiter.api.Test;

class ProjectionPushDownRuleTest {
    private final ProjectionPushDownRule rule = new ProjectionPushDownRule();

    private static QueryTree scan(String table) {
        return new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, table, List.of(), List.of(), 0);
    }

    private static QueryTree project(QueryTree child, ColumnReference... columns) {
        return new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(child), 0, List.of(columns), false);
    }

    private static ColumnReference col(String table, String column) {
        return new ColumnReference(table, column);
    }

    @Test
    void matchesOnlyProjectNodes() {
        assertEquals("ProjectionPushDown", rule.getRuleName());
        assertTrue(rule.matches(project(scan("students"), col("students", "id"))));
        assertFalse(rule.matches(scan("students")));
        assertFalse(rule.matches(null));
    }

    @Test
    void returnsNonProjectNodeUnchanged() {
        QueryTree scan = scan("students");
        assertSame(scan, rule.apply(scan, null));
    }

    @Test
    void collapsesChainedProjectionsIntoOutermost() {
        QueryTree chained = project(
            project(
                project(scan("students"), col("students", "id"), col("students", "name"), col("students", "gpa")),
                col("students", "id"), col("students", "name")),
            col("students", "name"));
        assertEquals(
            "PROJECT [columns=[students.name]]\n"
                + "  TABLE_SCAN [table=students]\n",
            new QueryPlanPrinter().printPlan(rule.apply(chained, null)));
    }

    @Test
    void keepsSingleProjectionAsIs() {
        QueryTree single = project(scan("students"), col("students", "name"));
        assertSame(single, rule.apply(single, null));
    }

    // students(id, name, gpa), takes(nim, code, grade)
    private static Main.InMemorySchemaCatalog catalog() {
        Main.InMemorySchemaCatalog catalog = new Main.InMemorySchemaCatalog();
        catalog.addTable(new TableSchema("students", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 4, false),
            new ColumnDefinition("name", DataType.VARCHAR, 50, false),
            new ColumnDefinition("gpa", DataType.FLOAT, 4, false)), List.of("id"), List.of()));
        catalog.addTable(new TableSchema("takes", List.of(
            new ColumnDefinition("nim", DataType.INTEGER, 4, false),
            new ColumnDefinition("code", DataType.VARCHAR, 10, false),
            new ColumnDefinition("grade", DataType.CHAR, 2, false)), List.of(), List.of()));
        catalog.addTable(new TableSchema("courses", List.of(
            new ColumnDefinition("code", DataType.VARCHAR, 10, false),
            new ColumnDefinition("credits", DataType.INTEGER, 4, false)), List.of("code"), List.of()));
        return catalog;
    }

    // resolve kolom milik node pertama bertipe type
    private static List<String> usedColumns(String sql, QueryNodeType type) {
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog()).bind(tree);
        return ProjectionPushDownRule.resolvedColumnsUsedBy(find(tree, type), bindings).stream()
            .map(ColumnReference::toString).toList();
    }

    private static QueryTree find(QueryTree node, QueryNodeType type) {
        if (node.getNodeType() == type) {
            return node;
        }
        for (QueryTree child : node.getChildren()) {
            QueryTree found = find(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void collectsColumnsFromWhereIncludingArithmetic() {
        assertEquals(List.of("students.gpa", "students.id"),
            usedColumns("SELECT name FROM students WHERE gpa * 2 > id;", QueryNodeType.SELECT));
    }

    @Test
    void collectsColumnsFromJoinCondition() {
        assertEquals(List.of("students.id", "takes.nim"),
            usedColumns("SELECT name FROM students JOIN takes ON students.id = takes.nim;", QueryNodeType.JOIN));
    }

    @Test
    void collectsColumnsFromOrderBy() {
        assertEquals(List.of("students.gpa"),
            usedColumns("SELECT name FROM students ORDER BY gpa DESC;", QueryNodeType.SORT));
    }

    @Test
    void resolvesUnqualifiedProjectionColumnsToTheirTable() {
        assertEquals(List.of("takes.grade", "students.name"),
            usedColumns("SELECT grade, name FROM students, takes;", QueryNodeType.PROJECT));
    }

    // parse + bind, opsional seleksi dulu, lalu proyeksi
    private String optimize(String sql, boolean pushSelections) {
        Main.InMemorySchemaCatalog catalog = catalog();
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog).bind(tree);
        OptimizationContext context = new OptimizationContext(bindings, catalog, catalog, null);
        if (pushSelections) {
            tree = applyBelow(tree, new SelectionPushDownRule(), context);
        }
        return new QueryPlanPrinter().printPlan(rule.apply(tree, context));
    }

    private static QueryTree applyBelow(QueryTree node, IOptimizationRule other, OptimizationContext context) {
        if (other.matches(node)) {
            return other.apply(node, context);
        }
        List<QueryTree> children = node.getChildren().stream()
            .map(child -> applyBelow(child, other, context)).toList();
        return children.isEmpty() ? node : QueryTreeRewrites.withChildren(node, children);
    }

    @Test
    void pushesProjectionsBelowCartesianKeepingWhereColumns() {
        assertEquals(
            "PROJECT [columns=[students.name]]\n"
                + "  SELECT [conditions=[((students.id = takes.nim) AND (takes.grade = 'A'))]]\n"
                + "    CARTESIAN\n"
                + "      PROJECT [columns=[students.id, students.name]]\n"
                + "        TABLE_SCAN [table=students]\n"
                + "      PROJECT [columns=[takes.nim, takes.grade]]\n"
                + "        TABLE_SCAN [table=takes]\n",
            optimize("SELECT students.name FROM students, takes "
                + "WHERE students.id = takes.nim AND takes.grade = 'A';", false));
    }

    @Test
    void dropsSelectionColumnsAboveTheirPushedSelection() {
        assertEquals(
            "PROJECT [columns=[students.name]]\n"
                + "  SELECT [conditions=[(students.id = takes.nim)]]\n"
                + "    CARTESIAN\n"
                + "      PROJECT [columns=[students.id, students.name]]\n"
                + "        SELECT [conditions=[(students.gpa > 3.0)]]\n"
                + "          TABLE_SCAN [table=students]\n"
                + "      PROJECT [columns=[takes.nim]]\n"
                + "        SELECT [conditions=[(takes.grade = 'A')]]\n"
                + "          TABLE_SCAN [table=takes]\n",
            optimize("SELECT students.name FROM students, takes "
                + "WHERE students.gpa > 3.0 AND takes.grade = 'A' AND students.id = takes.nim;", true));
    }

    @Test
    void keepsJoinColumnsEvenWhenNotDisplayed() {
        String plan = optimize("SELECT students.name FROM students JOIN takes ON students.id = takes.nim;", false);
        assertTrue(plan.contains("PROJECT [columns=[students.id, students.name]]"), plan);
        assertTrue(plan.contains("PROJECT [columns=[takes.nim]]"), plan);
    }

    @Test
    void keepsOrderByColumns() {
        String plan = optimize("SELECT students.name FROM students, takes ORDER BY takes.grade;", false);
        assertTrue(plan.contains("PROJECT [columns=[takes.grade]]"), plan);
        assertTrue(plan.contains("PROJECT [columns=[students.name]]\n        TABLE_SCAN [table=students]"), plan);
    }

    @Test
    void keepsFinalColumnOrderFromSelectClause() {
        assertTrue(optimize("SELECT takes.grade, students.name FROM students, takes;", false)
            .startsWith("PROJECT [columns=[takes.grade, students.name]]\n"));
    }

    @Test
    void skipsProjectionWhenSideNeedsAllOrNoColumns() {
        assertEquals(1, countProjects(optimize("SELECT * FROM students, takes;", false)));
        String plan = optimize("SELECT students.name FROM students, takes;", false);
        assertTrue(plan.endsWith("    TABLE_SCAN [table=takes]\n"), plan);
    }

    @Test
    void addsNoInnerProjectionForSingleTable() {
        assertEquals(1, countProjects(optimize("SELECT name FROM students WHERE gpa > 3.0;", true)));
    }

    @Test
    void isIdempotent() {
        Main.InMemorySchemaCatalog catalog = catalog();
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(
            "SELECT students.name FROM students JOIN takes ON students.id = takes.nim;");
        OptimizationContext context = new OptimizationContext(
            new QueryBinder(catalog).bind(tree), catalog, catalog, null);
        QueryTree once = rule.apply(tree, context);
        QueryPlanPrinter printer = new QueryPlanPrinter();
        assertEquals(printer.printPlan(once), printer.printPlan(rule.apply(once, context)));
    }

    @Test
    void onlyCollapsesWithoutBindings() {
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(
            "SELECT students.name FROM students, takes;");
        assertEquals(1, countProjects(new QueryPlanPrinter().printPlan(rule.apply(tree, null))));
    }

    @Test
    void dropsInnerJoinColumnsAfterTheyAreUsed() {
        assertEquals(
            "PROJECT [columns=[students.name]]\n"
                + "  SELECT [conditions=[(takes.code = courses.code)]]\n"
                + "    CARTESIAN\n"
                + "      PROJECT [columns=[students.name, takes.code]]\n"
                + "        SELECT [conditions=[(students.id = takes.nim)]]\n"
                + "          CARTESIAN\n"
                + "            PROJECT [columns=[students.id, students.name]]\n"
                + "              TABLE_SCAN [table=students]\n"
                + "            PROJECT [columns=[takes.nim, takes.code]]\n"
                + "              TABLE_SCAN [table=takes]\n"
                + "      PROJECT [columns=[courses.code]]\n"
                + "        TABLE_SCAN [table=courses]\n",
            optimize("SELECT students.name FROM students, takes, courses "
                + "WHERE students.id = takes.nim AND takes.code = courses.code;", true));
    }

    @Test
    void skipsProjectionWhenChildOutputAlreadyMatches() {
        assertEquals(
            "PROJECT [columns=[students.name, takes.code]]\n"
                + "  SELECT [conditions=[(takes.code = courses.code)]]\n"
                + "    CARTESIAN\n"
                + "      CARTESIAN\n"
                + "        PROJECT [columns=[students.name]]\n"
                + "          TABLE_SCAN [table=students]\n"
                + "        PROJECT [columns=[takes.code]]\n"
                + "          TABLE_SCAN [table=takes]\n"
                + "      PROJECT [columns=[courses.code]]\n"
                + "        TABLE_SCAN [table=courses]\n",
            optimize("SELECT students.name, takes.code FROM students, takes, courses "
                + "WHERE takes.code = courses.code;", true));
    }

    @Test
    void keepsSharedColumnsOfNaturalJoin() {
        String plan = optimize("SELECT takes.grade FROM takes NATURAL JOIN courses;", false);
        assertTrue(plan.contains("JOIN [natural=true"), plan);
        assertTrue(plan.contains("PROJECT [columns=[takes.code, takes.grade]]"), plan);
        assertTrue(plan.contains("PROJECT [columns=[courses.code]]"), plan);
    }

    @Test
    void keepsSortAndLimitAboveJoin() {
        String plan = optimize("SELECT students.name FROM students JOIN takes ON students.id = takes.nim "
            + "WHERE takes.grade = 'A' ORDER BY students.gpa DESC LIMIT 5;", true);
        assertTrue(plan.startsWith("PROJECT [columns=[students.name]]\n"
            + "  LIMIT [limit=5]\n"
            + "    SORT [orderBy=[students.gpa DESC]]\n"
            + "      JOIN"), plan);
        // students butuh id, name, gpa = semua kolom, jadi ga perlu PROJECT
        assertTrue(plan.contains("JOIN [conditions=[(students.id = takes.nim)]]\n"
            + "        TABLE_SCAN [table=students]\n"), plan);
        assertTrue(plan.contains("PROJECT [columns=[takes.nim]]"), plan);
    }

    @Test
    void neverDropsAColumnThatIsUsedHigherUp() {
        List<String> queries = List.of(
            "SELECT students.name FROM students, takes, courses "
                + "WHERE students.id = takes.nim AND takes.code = courses.code AND courses.credits > 2;",
            "SELECT takes.grade, students.name FROM students JOIN takes ON students.id = takes.nim "
                + "WHERE students.gpa > 3.0 ORDER BY takes.code;",
            "SELECT * FROM takes NATURAL JOIN courses WHERE courses.credits > 2;",
            "SELECT courses.credits FROM students, takes, courses WHERE takes.grade = 'A' "
                + "ORDER BY students.name LIMIT 10;");
        for (String sql : queries) {
            Main.InMemorySchemaCatalog catalog = catalog();
            QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
            BindingContext bindings = new QueryBinder(catalog).bind(tree);
            OptimizationContext context = new OptimizationContext(bindings, catalog, catalog, null);
            QueryTree result = rule.apply(applyBelow(tree, new SelectionPushDownRule(), context), context);
            assertColumnsAvailable(result, bindings, sql);
        }
    }

    // tiap kolom yang dipakai node harus ada di output anak-anaknya
    private static void assertColumnsAvailable(QueryTree node, BindingContext bindings, String sql) {
        if (node.getChildren().isEmpty()) {
            return;
        }
        Set<ColumnReference> available = new HashSet<>();
        for (QueryTree child : node.getChildren()) {
            available.addAll(outputOf(child, bindings));
            assertColumnsAvailable(child, bindings, sql);
        }
        for (ColumnReference used : ProjectionPushDownRule.resolvedColumnsUsedBy(node, bindings)) {
            assertTrue(available.contains(used), used + " hilang di bawah " + node.getNodeType() + " untuk " + sql);
        }
    }

    private static List<ColumnReference> outputOf(QueryTree node, BindingContext bindings) {
        if (node.getNodeType() == QueryNodeType.PROJECT) {
            return node.getProjectionColumns();
        }
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN) {
            TableBinding table = bindings.getTableBinding(node.getTableName());
            return table.getSchema().columns().stream()
                .map(column -> new ColumnReference(table.getAlias(), column.name())).toList();
        }
        List<ColumnReference> out = new ArrayList<>();
        for (QueryTree child : node.getChildren()) {
            out.addAll(outputOf(child, bindings));
        }
        return out;
    }

    private static int countProjects(String plan) {
        return plan.split("PROJECT", -1).length - 1;
    }

    @Test
    void returnsNullWhenColumnCannotBeResolved() {
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree("SELECT name FROM students;");
        assertNull(ProjectionPushDownRule.resolvedColumnsUsedBy(tree, null));
        assertNull(ProjectionPushDownRule.resolve(col(null, "name"), new BindingContext()));
    }
}
