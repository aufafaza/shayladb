package mdbms.optimizer;

import java.util.List;
import mdbms.models.query.ParsedQuery;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.exception.QuerySemanticException;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryOptimizerPlanTest {
    private QueryOptimizer optimizer() {
        Main.InMemorySchemaCatalog catalog = new Main.InMemorySchemaCatalog();
        catalog.addTable(new TableSchema("students", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 4, false),
            new ColumnDefinition("name", DataType.VARCHAR, 50, false)), List.of("id"), List.of()));
        catalog.addTable(new TableSchema("takes", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 4, false),
            new ColumnDefinition("code", DataType.VARCHAR, 10, false)), List.of(), List.of()));
        return new QueryOptimizer(catalog, new ShaylaParser(new ShaylaLexer("")));
    }

    @Test
    void producesBoundPlanFromDummySchemaWithoutStatisticsOrOptimization() {
        String sql = "SELECT * FROM students WHERE id >= 2 ORDER BY name LIMIT 5;";
        ParsedQuery parsed = optimizer().parseQuery(sql);

        assertEquals(sql, parsed.rawQuery());
        assertEquals(List.of("students"), parsed.targetTables());
        assertEquals("PROJECT [columns=[students.id, students.name]]\n"
            + "  LIMIT [limit=5]\n"
            + "    SORT [orderBy=[name ASC]]\n"
            + "      SELECT [conditions=[(id >= 2)]]\n"
            + "        TABLE_SCAN [table=students]\n",
            new QueryPlanPrinter().printPlan(parsed.queryTree()));
        assertEquals(0, parsed.queryTree().getEstimatedCost());
    }

    @Test
    void preservesCartesianAndWhereInsteadOfOptimizingIntoJoin() {
        ParsedQuery parsed = optimizer().parseQuery(
            "SELECT students.name FROM students, takes WHERE students.id = takes.id;");
        String plan = new QueryPlanPrinter().printPlan(parsed.queryTree());
        assertEquals(List.of("students", "takes"), parsed.targetTables());
        assertTrue(plan.contains("SELECT [conditions=[(students.id = takes.id)]]"));
        assertTrue(plan.contains("CARTESIAN\n"));
        assertFalse(plan.contains("JOIN"));
        assertFalse(plan.contains("estimated cost"));
        assertFalse(plan.contains("algorithm="));
    }

    @Test
    void rejectsUnknownTablesColumnsAndIncompatibleTypes() {
        assertThrows(QuerySemanticException.class, () -> optimizer().parseQuery("SELECT * FROM missing;"));
        assertThrows(QuerySemanticException.class, () -> optimizer().parseQuery("SELECT missing FROM students;"));
        assertThrows(QuerySemanticException.class, () -> optimizer().parseQuery(
            "SELECT name FROM students WHERE id = 'wrong type';"));
        assertThrows(QuerySyntaxException.class, () -> optimizer().parseQuery("SELECT FROM students;"));
    }

    @Test
    void preservesWritePayloadsInPlanOutput() {
        ParsedQuery parsed = optimizer().parseQuery("UPDATE students SET name = 'Alice' WHERE id = 1;");
        String plan = new QueryPlanPrinter().printPlan(parsed.queryTree());
        assertTrue(plan.contains("set=name = 'Alice'"));
        assertTrue(plan.contains("conditions=[(id = 1)]"));
    }
}
