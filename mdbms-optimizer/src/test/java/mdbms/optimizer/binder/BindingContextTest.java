package mdbms.optimizer.binder;

import java.util.List;
import java.util.Set;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BindingContextTest {
    private BindingContext context;
    private TableSchema studentSchema;
    private TableSchema courseSchema;

    @BeforeEach
    void setUp() {
        context = new BindingContext();

        studentSchema = new TableSchema(
            "students",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 4, false),
                new ColumnDefinition("name", DataType.VARCHAR, 50, false),
                new ColumnDefinition("gpa", DataType.FLOAT, 4, true)
            ),
            List.of("id"),
            List.of()
        );

        courseSchema = new TableSchema(
            "courses",
            List.of(
                new ColumnDefinition("code", DataType.VARCHAR, 10, false),
                new ColumnDefinition("title", DataType.VARCHAR, 100, false),
                new ColumnDefinition("credits", DataType.INTEGER, 4, false)
            ),
            List.of("code"),
            List.of()
        );
    }

    @Test
    void testRegisterAndGetTableBinding() {
        TableBinding tb = new TableBinding("students", "s", studentSchema);
        context.registerTableBinding("s", tb);

        assertEquals(tb, context.getTableBinding("s"));
        assertEquals(tb, context.getTableBinding("students"));
        // Case-insensitive check
        assertEquals(tb, context.getTableBinding("S"));
        assertEquals(tb, context.getTableBinding("STUDENTS"));
        assertNull(context.getTableBinding("unknown"));
    }

    @Test
    void testRegisterAndGetColumnBinding() {
        ColumnDefinition colId = studentSchema.columns().get(0);
        ColumnBinding cb = new ColumnBinding("students", "s", colId);

        ColumnReference refQualified = new ColumnReference("s", "id");
        ColumnReference refUnqualified = new ColumnReference(null, "id");

        context.registerColumnBinding(refQualified, cb);
        context.registerColumnBinding(refUnqualified, cb);

        assertEquals(cb, context.getColumnBinding(refQualified));
        assertEquals(cb, context.getColumnBinding(refUnqualified));
        assertEquals(DataType.INTEGER, cb.getDataType());
        assertEquals("id", cb.getColumnName());

        // Case-insensitive lookup check
        ColumnReference refCase = new ColumnReference("S", "ID");
        assertEquals(cb, context.getColumnBinding(refCase));
    }

    @Test
    void testGetReferencedTablesQualifiedExpression() {
        TableBinding tbStudent = new TableBinding("students", "s", studentSchema);
        TableBinding tbCourse = new TableBinding("courses", "c", courseSchema);
        context.registerTableBinding("s", tbStudent);
        context.registerTableBinding("c", tbCourse);

        // Expression: s.id = c.code
        ExpressionNode left = ExpressionNode.column("s", "id");
        ExpressionNode right = ExpressionNode.column("c", "code");
        ExpressionNode eq = ExpressionNode.comparison("=", left, right);
        ExpressionTree tree = new ExpressionTree(eq);

        Set<String> referenced = context.getReferencedTables(tree);
        assertEquals(2, referenced.size());
        assertTrue(referenced.contains("s"));
        assertTrue(referenced.contains("c"));
    }

    @Test
    void testGetReferencedTablesUnqualifiedExpression() {
        TableBinding tbStudent = new TableBinding("students", "students", studentSchema);
        context.registerTableBinding("students", tbStudent);

        ColumnDefinition colGpa = studentSchema.columns().get(2);
        ColumnBinding cbGpa = new ColumnBinding("students", "students", colGpa);
        context.registerColumnBinding(new ColumnReference(null, "gpa"), cbGpa);

        // Expression: gpa > 3.5
        ExpressionNode left = ExpressionNode.column(null, "gpa");
        ExpressionNode right = ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, "3.5");
        ExpressionNode gt = ExpressionNode.comparison(">", left, right);
        ExpressionTree tree = new ExpressionTree(gt);

        Set<String> referenced = context.getReferencedTables(tree);
        assertEquals(Set.of("students"), referenced);
    }

    @Test
    void testGetVisibleColumns() {
        TableBinding tbStudent = new TableBinding("students", "s", studentSchema);
        context.registerTableBinding("s", tbStudent);

        List<ColumnBinding> visible = context.getVisibleColumns(Set.of("s"));
        assertEquals(3, visible.size());
        assertEquals("id", visible.get(0).getColumnName());
        assertEquals("name", visible.get(1).getColumnName());
        assertEquals("gpa", visible.get(2).getColumnName());
        assertEquals("s", visible.get(0).getTableAlias());
        assertEquals("students", visible.get(0).getTableName());
    }

    @Test
    void testUnqualifiedLookupSingleTableFallback() {
        ColumnDefinition colName = studentSchema.columns().get(1); // "name"
        ColumnBinding cbName = new ColumnBinding("students", "s", colName);
        context.registerColumnBinding(new ColumnReference("s", "name"), cbName);

        // When unqualified "name" is looked up and only 1 table has it, it resolves to cbName
        ColumnBinding found = context.getColumnBinding(new ColumnReference(null, "name"));
        assertNotNull(found);
        assertEquals("name", found.getColumnName());
        assertEquals("students", found.getTableName());
    }

    @Test
    void testUnqualifiedLookupAmbiguousReturnsNull() {
        ColumnDefinition colId = studentSchema.columns().get(0); // "id"
        ColumnBinding cbStudentId = new ColumnBinding("students", "s", colId);
        ColumnBinding cbTakesId = new ColumnBinding("takes", "t", colId);

        context.registerColumnBinding(new ColumnReference("s", "id"), cbStudentId);
        context.registerColumnBinding(new ColumnReference("t", "id"), cbTakesId);

        // Ambiguous unqualified lookup without exact (null, "id") registration must return null
        ColumnBinding found = context.getColumnBinding(new ColumnReference(null, "id"));
        assertNull(found);
    }
}

