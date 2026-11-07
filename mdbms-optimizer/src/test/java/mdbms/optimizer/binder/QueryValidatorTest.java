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

class QueryValidatorTest {
    private MockSchemaCatalog catalog;
    private QueryBinder binder;
    private QueryValidator validator;

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

        binder = new QueryBinder(catalog);
        validator = new QueryValidator(binder);
    }

    @Test
    void testValidWhereConditions() {
        // SELECT * FROM students WHERE gpa >= 3.5 AND id = 1
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);

        ExpressionNode cond1 = ExpressionNode.comparison(
            ">=",
            ExpressionNode.column("students", "gpa"),
            ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.5")
        );
        ExpressionNode cond2 = ExpressionNode.comparison(
            "=",
            ExpressionNode.column("students", "id"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1")
        );
        ExpressionNode andCond = ExpressionNode.logicalAnd(cond1, cond2);

        QueryTree select = new QueryTree(
            QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(new ExpressionTree(andCond)),
            List.of(scan),
            0
        );

        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(select),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        assertTrue(validator.validate(project));
    }

    @Test
    void testArithmeticExpressionInWhereCondition() {
        // SELECT * FROM students WHERE gpa * 1.05 > 4.0
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);

        ExpressionNode mult = ExpressionNode.binary(
            "*",
            ExpressionNode.column(null, "gpa"),
            ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "1.05")
        );
        ExpressionNode gt = ExpressionNode.comparison(
            ">",
            mult,
            ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "4.0")
        );

        QueryTree select = new QueryTree(
            QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(new ExpressionTree(gt)),
            List.of(scan),
            0
        );
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(select),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        assertTrue(validator.validate(project));
    }

    @Test
    void testIncompatibleComparisonInWhereThrowsException() {
        // SELECT * FROM students WHERE gpa = 'cemerlang' (FLOAT = VARCHAR)
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);

        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "gpa"),
            ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "cemerlang")
        );

        QueryTree select = new QueryTree(
            QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(new ExpressionTree(eq)),
            List.of(scan),
            0
        );
        QueryTree project = new QueryTree(
            QueryNodeType.PROJECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(),
            List.of(select),
            0,
            List.of(new ColumnReference(null, "*")),
            false
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(project));
        assertTrue(ex.getMessage().contains("Cannot compare incompatible types"));
    }

    @Test
    void testArithmeticOnTextTypeThrowsException() {
        // SELECT * FROM students WHERE name + 10 = 'test'
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);

        ExpressionNode add = ExpressionNode.binary(
            "+",
            ExpressionNode.column(null, "name"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "10")
        );
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            add,
            ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "test")
        );

        QueryTree select = new QueryTree(
            QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT,
            null,
            List.of(new ExpressionTree(eq)),
            List.of(scan),
            0
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(select));
        assertTrue(ex.getMessage().contains("cannot be applied to non-numeric types"));
    }

    @Test
    void testValidInsertValues() {
        // INSERT INTO students VALUES (1, 'Alice', 3.8)
        QueryTree insert = new QueryTree(QueryNodeType.INSERT, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        insert.setInsertValues(List.of(
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "Alice")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.8"))
        ));

        assertTrue(validator.validate(insert));
    }

    @Test
    void testInsertValueCountMismatchThrowsException() {
        // INSERT INTO students VALUES (1, 'Alice') -> missing gpa
        QueryTree insert = new QueryTree(QueryNodeType.INSERT, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        insert.setInsertValues(List.of(
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "Alice"))
        ));

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(insert));
        assertTrue(ex.getMessage().contains("value count"));
    }

    @Test
    void testInsertValueTypeMismatchThrowsException() {
        // INSERT INTO students VALUES ('satu', 'Alice', 3.8) -> 'satu' for integer id
        QueryTree insert = new QueryTree(QueryNodeType.INSERT, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        insert.setInsertValues(List.of(
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "satu")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "Alice")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.8"))
        ));

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(insert));
        assertTrue(ex.getMessage().contains("Type mismatch on column 'id'"));
    }

    @Test
    void testValidUpdateAssignment() {
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

        assertTrue(validator.validate(update));
    }

    @Test
    void testUpdateAssignmentTypeMismatchThrowsException() {
        // UPDATE students SET gpa = 'cumlaude'
        QueryTree update = new QueryTree(QueryNodeType.UPDATE, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        update.setUpdateAssignment(
            new ColumnReference(null, "gpa"),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "cumlaude"))
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(update));
        assertTrue(ex.getMessage().contains("Cannot assign VARCHAR to column 'gpa' of type FLOAT"));
    }

    @Test
    void testValidatorWithQueryErrorCollectorRecordsError() {
        mdbms.optimizer.exception.QueryErrorCollector collector = new mdbms.optimizer.exception.QueryErrorCollector();
        QueryValidator valWithCollector = new QueryValidator(catalog, collector);

        // INSERT with wrong count
        QueryTree insert = new QueryTree(QueryNodeType.INSERT, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        insert.setInsertValues(List.of(
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1"))
        ));

        assertThrows(QuerySemanticException.class, () -> valWithCollector.validate(insert));
        assertTrue(collector.hasError());
        assertTrue(collector.hasFatalError());
        assertEquals(1, collector.getAllExceptions().size());
    }

    @Test
    void testInsertNarrowingFloatToIntThrowsException() {
        // students: id (INTEGER), name (VARCHAR), gpa (FLOAT)
        // Inserting float 1.5 into id (INTEGER) is narrowing -> rejected
        QueryTree insert = new QueryTree(QueryNodeType.INSERT, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        insert.setInsertValues(List.of(
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "1.5")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "Bob")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.5"))
        ));

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(insert));
        assertTrue(ex.getMessage().contains("Type mismatch on column 'id'"));
    }

    @Test
    void testInsertWideningIntToFloatAllowed() {
        // students: id (INTEGER), name (VARCHAR), gpa (FLOAT)
        // Inserting integer 4 into gpa (FLOAT) is widening -> allowed
        QueryTree insert = new QueryTree(QueryNodeType.INSERT, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        insert.setInsertValues(List.of(
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "2")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "Charlie")),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "4"))
        ));

        assertTrue(validator.validate(insert));
    }

    @Test
    void testUpdateUnknownTargetColumnThrowsException() {
        // UPDATE students SET non_existent = 1
        QueryTree update = new QueryTree(QueryNodeType.UPDATE, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        update.setUpdateAssignment(
            new ColumnReference(null, "non_existent"),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1"))
        );

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(update));
        assertTrue(ex.getMessage().contains("non_existent"));
    }

    @Test
    void testUpdateWideningIntToFloatAllowed() {
        // UPDATE students SET gpa = 4 (INTEGER assigned to FLOAT column)
        QueryTree update = new QueryTree(QueryNodeType.UPDATE, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        update.setUpdateAssignment(
            new ColumnReference(null, "gpa"),
            new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "4"))
        );

        assertTrue(validator.validate(update));
    }

    @Test
    void testDeleteWithIncompatibleComparisonThrowsException() {
        // DELETE FROM students WHERE gpa = 'tinggi' (FLOAT = VARCHAR)
        QueryTree delete = new QueryTree(QueryNodeType.DELETE, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        ExpressionNode eq = ExpressionNode.comparison(
            "=",
            ExpressionNode.column(null, "gpa"),
            ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, "tinggi")
        );
        delete.setConditions(List.of(new ExpressionTree(eq)));

        QuerySemanticException ex = assertThrows(QuerySemanticException.class, () -> validator.validate(delete));
        assertTrue(ex.getMessage().contains("Cannot compare incompatible types"));
    }

    @Test
    void testValidateLimitNodeRequiresLimitValue() {
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "students", List.of(), List.of(), 0);
        QueryTree limitWithoutValue = new QueryTree(QueryNodeType.LIMIT, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(scan), 0);

        assertThrows(QuerySemanticException.class, () -> validator.validate(limitWithoutValue));

        limitWithoutValue.setLimit(10);
        assertTrue(validator.validate(limitWithoutValue));
    }

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

