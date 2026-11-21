package mdbms.optimizer;

import java.util.ArrayList;
import java.util.List;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.DropBehavior;
import mdbms.models.schema.ForeignKeyAction;
import mdbms.models.schema.ForeignKeyConstraint;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.QueryBinder;
import mdbms.optimizer.binder.TableBinding;
import mdbms.optimizer.exception.QuerySemanticException;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.lexer.TokenType;
import mdbms.optimizer.parser.ShaylaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryAnalyzerFeaturesTest {
    private Main.InMemorySchemaCatalog catalog;
    private QueryOptimizer optimizer;
    private final ShaylaParser parser = new ShaylaParser(new ShaylaLexer(""));

    @BeforeEach void setUp() {
        catalog = new Main.InMemorySchemaCatalog();
        catalog.addTable(new TableSchema("students", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 4, false),
            new ColumnDefinition("name", DataType.VARCHAR, 50, true)), List.of("id"), List.of()));
        optimizer = new QueryOptimizer(catalog, parser);
    }

    @Test void tokenizesDropAndAs() {
        var stream = new ShaylaLexer("drop TABLE students; AS").tokenizeToStream();
        stream.expect(TokenType.dropTok);
        stream.expect(TokenType.tableTok);
        stream.expect(TokenType.idTok);
        stream.expect(TokenType.semicolon);
        stream.expect(TokenType.asTok);
        assertTrue(stream.isAtEnd());
    }

    @Test void parsesAllTypesPrimaryKeyAndForeignKeyActions() {
        QueryTree tree = parser.parseToTree("CREATE TABLE grades (id INTEGER PRIMARY KEY, score FLOAT, "
            + "grade CHAR(2) NOT NULL, label VARCHAR(50), student_id INT, "
            + "FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE ON UPDATE RESTRICT);");
        assertEquals(QueryNodeType.CREATE_TABLE, tree.getNodeType());
        TableSchema schema = tree.getTableDefinition();
        assertEquals(List.of(DataType.INTEGER, DataType.FLOAT, DataType.CHAR, DataType.VARCHAR, DataType.INTEGER),
            schema.columns().stream().map(ColumnDefinition::type).toList());
        assertFalse(schema.columns().get(0).nullable());
        assertFalse(schema.columns().get(2).nullable());
        assertEquals(2, schema.columns().get(2).length());
        assertEquals(List.of("id"), schema.primaryKey());
        assertEquals(new ForeignKeyConstraint("student_id", "students", "id",
            ForeignKeyAction.CASCADE, ForeignKeyAction.RESTRICT), schema.foreignKeys().get(0));
    }

    @Test void bindsNewTableAndCanonicalizesForeignKeysWithoutChangingCatalog() {
        var parsed = optimizer.parseQuery("CREATE TABLE grades (student_id INT REFERENCES STUDENTS(ID), "
            + "term INT, PRIMARY KEY (STUDENT_ID, term));");
        TableSchema schema = parsed.queryTree().getTableDefinition();
        assertEquals(List.of("student_id", "term"), schema.primaryKey());
        assertEquals("students", schema.foreignKeys().get(0).referencedTable());
        assertEquals("id", schema.foreignKeys().get(0).referencedColumn());
        assertEquals(List.of("grades"), parsed.targetTables());
        assertNull(catalog.getTableSchema("grades"));
        assertEquals(schema, optimizer.optimizeQuery(parsed).queryTree().getTableDefinition());
    }

    @Test void supportsSelfReferencingForeignKey() {
        assertDoesNotThrow(() -> optimizer.parseQuery(
            "CREATE TABLE nodes (id INT PRIMARY KEY, parent INT REFERENCES nodes(id));"));
    }

    @Test void rejectsMalformedDefinitionsAndDropOptions() {
        for (String sql : List.of("CREATE TABLE t ()", "CREATE TABLE t (id)",
                "CREATE TABLE t (id BOOLEAN)", "CREATE TABLE t (name VARCHAR)",
                "CREATE TABLE t (name CHAR(0))", "CREATE TABLE t (name VARCHAR(-1))",
                "CREATE TABLE t (name VARCHAR(2147483648))", "CREATE TABLE t (id INT,)",
                "CREATE TABLE t (id INT PRIMARY KEY, PRIMARY KEY(id))",
                "CREATE TABLE t (id INT NULL NOT NULL)",
                "CREATE TABLE t (id INT REFERENCES students(id) ON DELETE CASCADE ON DELETE RESTRICT)",
                "CREATE TABLE t (id INT, FOREIGN KEY(id, other) REFERENCES students(id))",
                "DROP TABLE", "DROP TABLE students CASCADE RESTRICT", "DROP INDEX idx")) {
            assertThrows(QuerySyntaxException.class, () -> parser.parseToTree(sql), sql);
        }
    }

    @Test void rejectsDuplicateTablesColumnsAndInvalidKeys() {
        for (String sql : List.of("CREATE TABLE STUDENTS (id INT)",
                "CREATE TABLE t (id INT, ID FLOAT)",
                "CREATE TABLE t (id INT, PRIMARY KEY(missing))",
                "CREATE TABLE t (id INT, PRIMARY KEY(id, ID))",
                "CREATE TABLE t (id INT, FOREIGN KEY(missing) REFERENCES students(id))",
                "CREATE TABLE t (id INT REFERENCES missing(id))",
                "CREATE TABLE t (id INT REFERENCES students(missing))",
                "CREATE TABLE t (name VARCHAR(50) REFERENCES students(name))",
                "CREATE TABLE t (id FLOAT REFERENCES students(id))",
                "CREATE TABLE t (id INT NOT NULL REFERENCES students(id) ON DELETE SET NULL)")) {
            assertThrows(QuerySemanticException.class, () -> optimizer.parseQuery(sql), sql);
        }
        assertEquals(List.of("students"), catalog.listTable());
    }

    @Test void dropDefaultsToRestrictAndCascadeIsExplicit() {
        catalog.addTable(new TableSchema("grades", List.of(new ColumnDefinition("student_id", DataType.INTEGER, 4, false)),
            List.of(), List.of(new ForeignKeyConstraint("student_id", "students", "id",
                ForeignKeyAction.NO_ACTION, ForeignKeyAction.NO_ACTION))));
        assertThrows(QuerySemanticException.class, () -> optimizer.parseQuery("DROP TABLE students"));
        assertThrows(QuerySemanticException.class, () -> optimizer.parseQuery("DROP TABLE students RESTRICT"));
        QueryTree drop = optimizer.parseQuery("DROP TABLE STUDENTS CASCADE").queryTree();
        assertEquals(QueryNodeType.DROP_TABLE, drop.getNodeType());
        assertEquals("students", drop.getTableName());
        assertEquals(DropBehavior.CASCADE, drop.getDropBehavior());
        assertEquals(DropBehavior.RESTRICT, parser.parseToTree("DROP TABLE grades").getDropBehavior());
        assertThrows(QuerySemanticException.class, () -> optimizer.parseQuery("DROP TABLE missing"));
        assertEquals(2, catalog.listTable().size());
    }

    @Test void aliasesSurviveOptimizationAndRebindingInASelfJoin() {
        var parsed = optimizer.parseQuery("SELECT a.name, b.name FROM students AS a JOIN students AS b "
            + "ON a.id < b.id WHERE a.id >= 1 ORDER BY b.name LIMIT 5;");
        QueryTree optimized = optimizer.optimizeQuery(parsed).queryTree();
        List<QueryTree> scans = new ArrayList<>();
        collectScans(optimized, scans);
        assertEquals(2, scans.size());
        assertEquals(List.of("a", "b"), scans.stream().map(QueryTree::getTableAlias).sorted().toList());
        assertTrue(scans.stream().allMatch(scan -> scan.getTableName().equals("students")));
        BindingContext context = new QueryBinder(catalog).bind(optimized);
        assertEquals(2, context.getTableBindings().size());
        assertNotNull(context.getTableBindingByAlias("A"));
        assertNull(context.getTableBinding("students"));
        assertEquals(List.of("students"), parsed.targetTables());
        assertTrue(new QueryPlanPrinter().printPlan(optimized).contains("alias=a"));
    }

    @Test void expandsStarForDistinctAliasesAndSupportsQualifiedStar() {
        QueryTree all = optimizer.parseQuery("SELECT * FROM students AS a, students AS b").queryTree();
        assertEquals(List.of(new ColumnReference("a", "id"), new ColumnReference("a", "name"),
            new ColumnReference("b", "id"), new ColumnReference("b", "name")), all.getProjectionColumns());
        QueryTree qualified = optimizer.parseQuery("SELECT s.* FROM students AS s").queryTree();
        assertEquals(List.of(new ColumnReference("s", "id"), new ColumnReference("s", "name")),
            qualified.getProjectionColumns());
    }

    @Test void rejectsCollidingNamesUnknownAliasesAndAmbiguousColumns() {
        for (String sql : List.of("SELECT * FROM students AS s, students AS S",
                "SELECT * FROM students, students", "SELECT id FROM students AS a, students AS b",
                "SELECT x.id FROM students AS s", "SELECT students.id FROM students AS s",
                "SELECT id FROM students AS s WHERE x.id = 1")) {
            assertThrows(QuerySemanticException.class, () -> optimizer.parseQuery(sql), sql);
        }
        assertThrows(QuerySyntaxException.class, () -> parser.parseToTree("SELECT * FROM students AS"));
    }

    @Test void bindingContextRejectsDuplicateAliasesWithoutOverwriting() {
        BindingContext context = new BindingContext();
        TableBinding original = new TableBinding("students", "s", catalog.getTableSchema("students"));
        context.registerTableBinding("s", original);
        assertThrows(QuerySemanticException.class, () -> context.registerTableBinding("S", original));
        assertSame(original, context.getTableBinding("s"));
        assertEquals(1, context.getTableBindings().size());
    }

    private static void collectScans(QueryTree node, List<QueryTree> scans) {
        if (node.getNodeType() == QueryNodeType.TABLE_SCAN) scans.add(node);
        node.getChildren().forEach(child -> collectScans(child, scans));
    }
}
