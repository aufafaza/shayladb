package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
