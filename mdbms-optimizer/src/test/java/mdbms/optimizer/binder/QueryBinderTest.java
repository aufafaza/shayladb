package mdbms.optimizer.binder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.exception.QuerySemanticException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryBinderTest {
    private MockSchemaCatalog catalog;
    private QueryBinder binder;

    @BeforeEach
    void setUp() {
        catalog = new MockSchemaCatalog();
        catalog.addTable(new TableSchema(
            "students",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 4, false),
                new ColumnDefinition("name", DataType.VARCHAR, 50, false),
                new ColumnDefinition("gpa", DataType.FLOAT, 4, true)
            ),
            List.of("id"),
            List.of()
        ));

        catalog.addTable(new TableSchema(
            "courses",
            List.of(
                new ColumnDefinition("code", DataType.VARCHAR, 10, false),
                new ColumnDefinition("title", DataType.VARCHAR, 100, false),
                new ColumnDefinition("credits", DataType.INTEGER, 4, false)
            ),
            List.of("code"),
            List.of()
        ));

        catalog.addTable(new TableSchema(
            "takes",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 4, false),
                new ColumnDefinition("code", DataType.VARCHAR, 10, false),
                new ColumnDefinition("grade", DataType.VARCHAR, 2, true)
            ),
            List.of("id", "code"),
            List.of()
        ));

        binder = new QueryBinder(catalog);
    }

    @Test
    void testBindSingleTableValidColumns() {
        // SELECT id, students.name FROM students
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(scan),
            0,
            List.of(new ColumnReference(null, "id"), new ColumnReference("students", "name")),
            false
        );

        BindingContext ctx = binder.bind(project);
        assertNotNull(ctx.getTableBinding("students"));

        ColumnBinding idBinding = ctx.getColumnBinding(new ColumnReference(null, "id"));
        assertNotNull(idBinding);
        assertEquals("students", idBinding.getTableName());
        assertEquals("id", idBinding.getColumnName());
        assertEquals(DataType.INTEGER, idBinding.getDataType());

        ColumnBinding nameBinding = ctx.getColumnBinding(new ColumnReference("students", "name"));
        assertNotNull(nameBinding);
        assertEquals(DataType.VARCHAR, nameBinding.getDataType());
    }

    @Test
    void testBindTableNotFoundThrowsException() {
        // SELECT * FROM unknown_table
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "unknown_table", List.of(), List.of(), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(scan),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> binder.bind(project));
        assertTrue(ex.getMessage().contains("unknown_table"));
    }

    @Test
    void testBindColumnNotFoundQualifiedThrowsException() {
        // SELECT students.missing_col FROM students
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(scan),
            0,
            List.of(new ColumnReference("students", "missing_col")),
            false
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> binder.bind(project));
        assertTrue(ex.getMessage().contains("missing_col"));
    }

    @Test
    void testBindColumnNotFoundUnqualifiedThrowsException() {
        // SELECT missing_col FROM students
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(scan),
            0,
            List.of(new ColumnReference(null, "missing_col")),
            false
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> binder.bind(project));
        assertTrue(ex.getMessage().contains("missing_col"));
    }

    @Test
    void testBindAmbiguousColumnThrowsException() {
        // SELECT id FROM students, takes (both have column 'id')
        QueryTree scan1 = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree scan2 = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "takes", List.of(), List.of(), 0);
        QueryTree cartesian = new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(scan1, scan2), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(cartesian),
            0,
            List.of(new ColumnReference(null, "id")),
            false
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> binder.bind(project));
        assertTrue(ex.getMessage().contains("ambiguous"));
        assertTrue(ex.getMessage().contains("id"));
    }

    @Test
    void testBindDisambiguatedColumnInMultiTableSucceeds() {
        // SELECT students.id, takes.id FROM students, takes
        QueryTree scan1 = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree scan2 = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "takes", List.of(), List.of(), 0);
        QueryTree cartesian = new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(scan1, scan2), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(cartesian),
            0,
            List.of(new ColumnReference("students", "id"), new ColumnReference("takes", "id")),
            false
        );

        BindingContext ctx = binder.bind(project);
        assertNotNull(ctx.getColumnBinding(new ColumnReference("students", "id")));
        assertNotNull(ctx.getColumnBinding(new ColumnReference("takes", "id")));
    }

    @Test
    void testExpandSelectStarSingleTable() {
        // SELECT * FROM students
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(scan),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        BindingContext ctx = binder.bind(project);

        List<ColumnReference> cols = project.getProjectionColumns();
        assertEquals(3, cols.size());
        assertEquals("id", cols.get(0).getColumnName());
        assertEquals("name", cols.get(1).getColumnName());
        assertEquals("gpa", cols.get(2).getColumnName());
        assertEquals("students", cols.get(0).getTableName());

        // Verify each column is bound in context
        for (ColumnReference col : cols) {
            assertNotNull(ctx.getColumnBinding(col));
        }
    }

    @Test
    void testResolveNaturalJoin() {
        // students NATURAL JOIN takes
        // students: (id, name, gpa), takes: (id, code, grade) -> common column: 'id'
        QueryTree scanStudents = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree scanTakes = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "takes", List.of(), List.of(), 0);
        QueryTree naturalJoin = new QueryTree(
            QueryNodeType.JOIN,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(scanStudents, scanTakes),
            0,
            List.of(),
            true
        );
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(naturalJoin),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        binder.bind(project);

        // Join condition should have been populated
        assertFalse(naturalJoin.getConditions().isEmpty());
        ExpressionNode condRoot = naturalJoin.getConditions().get(0).getRoot();
        assertEquals(ExpressionNode.Type.COMPARISON, condRoot.getType());
        assertEquals("=", condRoot.getValue());
        assertEquals("id", condRoot.getLeft().getValue());
        assertEquals("id", condRoot.getRight().getValue());

        // SELECT * under natural join should eliminate duplicate 'id' column
        List<ColumnReference> expanded = project.getProjectionColumns();
        assertEquals(5, expanded.size()); // id (common), name, gpa, code, grade
        assertEquals("id", expanded.get(0).getColumnName());
    }

    @Test
    void testBindUpdateStatement() {
        // UPDATE students SET gpa = 3.9 WHERE id = 1
        QueryTree update = new QueryTree(QueryNodeType.UPDATE, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        update.setUpdateAssignment(
            new ColumnReference(null, "gpa"),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.9"))
        );
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "id"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1")
        );
        update.setConditions(List.of(new ExpressionTree(eq)));

        BindingContext ctx = binder.bind(update);
        assertNotNull(ctx.getTableBinding("students"));
        assertNotNull(ctx.getColumnBinding(new ColumnReference(null, "gpa")));
        assertNotNull(ctx.getColumnBinding(new ColumnReference(null, "id")));
    }

    @Test
    void testBindCreateIndexStatement() {
        // CREATE INDEX idx_name ON students(name)
        QueryTree createIndex = new QueryTree(QueryNodeType.CREATE_INDEX, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        createIndex.setIndexDefinition("idx_name", new ColumnReference(null, "name"));

        BindingContext ctx = binder.bind(createIndex);
        assertNotNull(ctx.getTableBinding("students"));
        assertNotNull(ctx.getColumnBinding(new ColumnReference(null, "name")));
    }

    @Test
    void testBindWithQueryErrorCollectorRecordsError() {
        mdbms.optimizer.exception.QueryErrorCollector collector = new mdbms.optimizer.exception.QueryErrorCollector();
        QueryBinder binderWithCollector = new QueryBinder(catalog, collector);

        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "non_existent", List.of(), List.of(), 0);
        assertThrows(QuerySemanticException.class, () -> binderWithCollector.bind(scan));

        assertTrue(collector.hasError());
        assertTrue(collector.hasFatalError());
        assertEquals(1, collector.getAllExceptions().size());
        assertTrue(collector.getAllExceptions().get(0).getMessage().contains("non_existent"));
    }

    @Test
    void testOrderByWithUnknownColumnThrowsException() {
        // SELECT id FROM students ORDER BY non_existent ASC
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree sort = new QueryTree(QueryNodeType.SORT, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(scan), 0);
        sort.setOrderByClauses(List.of(new mdbms.models.query.OrderByClause(
            new ColumnReference(null, "non_existent"),
            mdbms.models.query.SortDirection.ASC
        )));
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(sort),
            0,
            List.of(new ColumnReference(null, "id")),
            false
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> binder.bind(project));
        assertTrue(ex.getMessage().contains("non_existent"));
    }

    @Test
    void testWhereWithUnknownColumnThrowsException() {
        // SELECT id FROM students WHERE non_existent = 1
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "non_existent"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1")
        );
        QueryTree select = new QueryTree(QueryNodeType.SELECT, PhysicalAlgorithm.DEFAULT, null, List.of(new ExpressionTree(eq)), List.of(scan), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(select),
            0,
            List.of(new ColumnReference(null, "id")),
            false
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> binder.bind(project));
        assertTrue(ex.getMessage().contains("non_existent"));
    }

    @Test
    void testExpandSelectStarCartesianProduct() {
        // SELECT * FROM students, courses (cartesian)
        QueryTree scanStudents = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree scanCourses = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "courses", List.of(), List.of(), 0);
        QueryTree cartesian = new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(scanStudents, scanCourses), 0);
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(cartesian),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        binder.bind(project);

        List<ColumnReference> cols = project.getProjectionColumns();
        // students has 3 cols (id, name, gpa), courses has 3 cols (code, title, credits) -> total 6
        assertEquals(6, cols.size());
        assertEquals("id", cols.get(0).getColumnName());
        assertEquals("name", cols.get(1).getColumnName());
        assertEquals("gpa", cols.get(2).getColumnName());
        assertEquals("code", cols.get(3).getColumnName());
        assertEquals("title", cols.get(4).getColumnName());
        assertEquals("credits", cols.get(5).getColumnName());
    }

    @Test
    void testBindDeleteStatement() {
        // DELETE FROM students WHERE id = 5
        QueryTree delete = new QueryTree(QueryNodeType.DELETE, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "id"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "5")
        );
        delete.setConditions(List.of(new ExpressionTree(eq)));

        BindingContext ctx = binder.bind(delete);
        assertNotNull(ctx.getTableBinding("students"));
        assertNotNull(ctx.getColumnBinding(new ColumnReference(null, "id")));
    }

    @Test
    void testCreateIndexWithUnknownColumnThrowsException() {
        // CREATE INDEX idx ON students(missing_col)
        QueryTree createIndex = new QueryTree(QueryNodeType.CREATE_INDEX, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        createIndex.setIndexDefinition("idx", new ColumnReference(null, "missing_col"));

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> binder.bind(createIndex));
        assertTrue(ex.getMessage().contains("missing_col"));
    }

    // Mock Schema Catalog for testing without full storage engine
    private static class MockSchemaCatalog implements ISchemaCatalog {
        private final Map<String, TableSchema> tables = new HashMap<>();

        void addTable(TableSchema schema) {
            tables.put(schema.tableName().toLowerCase(), schema);
        }

        @Override
        public TableSchema getTableSchema(String table) {
            return table == null ? null : tables.get(table.toLowerCase());
        }

        @Override
        public List<String> listTable() {
            return List.copyOf(tables.keySet());
        }

        @Override
        public void updateTableSchema(TableSchema schema) {
            if (schema != null) {
                tables.put(schema.tableName().toLowerCase(), schema);
            }
        }

        @Override
        public List<IndexInfo> getIndexes(String table) {
            return List.of();
        }

        @Override
        public IndexInfo createIndex(String name, String table, String column) {
            return null;
        }
    }
}

