package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
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

class SelectionPushDownRuleTest {
    private final SelectionPushDownRule rule = new SelectionPushDownRule();

    // students(id, name, gpa), takes(nim, code, grade), courses(code, credits)
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

    // parse + bind, lalu apply rule ke SELECT teratas
    private String optimizeSelection(String sql) {
        Main.InMemorySchemaCatalog catalog = catalog();
        QueryTree tree = new ShaylaParser(new ShaylaLexer("")).parseToTree(sql);
        BindingContext bindings = new QueryBinder(catalog).bind(tree);
        OptimizationContext context = new OptimizationContext(bindings, catalog, catalog, null);
        return new QueryPlanPrinter().printPlan(rule.apply(findSelect(tree), context));
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

    @Test
    void matchesOnlySelectNodes() {
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, null, "students", List.of(), List.of(), 0);
        assertFalse(rule.matches(scan));
        assertFalse(rule.matches(null));
        ExpressionTree condition = new ExpressionTree(ExpressionNode.comparison("=",
            ExpressionNode.column("students", "id"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1")));
        assertTrue(rule.matches(QueryTreeRewrites.selection(condition, scan)));
        assertEquals("SelectionPushDown", rule.getRuleName());
    }

    @Test
    void returnsNonSelectNodeUnchanged() {
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, null, "students", List.of(), List.of(), 0);
        assertSame(scan, rule.apply(scan, null));
    }

    @Test
    void keepsSingleConditionAsOneSelection() {
        assertEquals(
            "SELECT [conditions=[(students.id = 1)]]\n"
                + "  TABLE_SCAN [table=students]\n",
            optimizeSelection("SELECT name FROM students WHERE students.id = 1;"));
    }

    @Test
    void splitsConjunctiveSelectionIntoChain() {
        assertEquals(
            "SELECT [conditions=[(students.id > 1)]]\n"
                + "  SELECT [conditions=[(students.gpa >= 3.5)]]\n"
                + "    SELECT [conditions=[(students.name <> 'x')]]\n"
                + "      TABLE_SCAN [table=students]\n",
            optimizeSelection("SELECT name FROM students "
                + "WHERE students.id > 1 AND students.gpa >= 3.5 AND students.name <> 'x';"));
    }

    @Test
    void keepsParenthesizedArithmeticInsideOneCondition() {
        assertEquals(
            "SELECT [conditions=[((students.gpa * 2) > 6)]]\n"
                + "  SELECT [conditions=[(students.id = 3)]]\n"
                + "    TABLE_SCAN [table=students]\n",
            optimizeSelection("SELECT name FROM students WHERE students.gpa * 2 > 6 AND students.id = 3;"));
    }
}
