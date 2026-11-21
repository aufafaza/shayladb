package mdbms.storage.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.ForeignKeyAction;
import mdbms.models.schema.ForeignKeyConstraint;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;

class SchemaScriptParserTest {

    // pastiin parse gagal dengan SCHEMA_VIOLATION dan pesannya nyebut potongan teks tertentu
    private static void assertRejected(String source, String expected) {
        StorageException e = assertThrows(StorageException.class, () -> SchemaScriptParser.parse(source));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, e.getCode());
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    // semua bentuk yang didukung kebaca jadi TableSchema dan IndexInfo yang bener
    @Test
    void parsesTablesConstraintsAndIndexes() {
        SchemaScript script = SchemaScriptParser.parse("""
                -- tabel mahasiswa
                create table students (
                  nim INT primary key,
                  name CHAR(100) NOT NULL,
                  gpa FLOAT
                );
                CREATE TABLE takes (
                  id INTEGER NOT NULL,
                  nim INTEGER NOT NULL,
                  code VARCHAR(30) NOT NULL,
                  PRIMARY KEY (id),
                  FOREIGN KEY (nim) REFERENCES students(nim) ON DELETE CASCADE ON UPDATE SET NULL,
                  FOREIGN KEY (code) REFERENCES courses(code)
                );
                CREATE INDEX idx_takes_nim ON takes(nim)
                """);

        assertEquals(new TableSchema("students", List.of(
                new ColumnDefinition("nim", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.CHAR, 100, false),
                new ColumnDefinition("gpa", DataType.FLOAT, 0, true)),
                List.of("nim"), List.of()), script.tables().get(0).schema());
        assertEquals(2, script.tables().get(0).line());

        TableSchema takes = script.tables().get(1).schema();
        assertEquals(List.of("id"), takes.primaryKey());
        assertEquals(List.of(
                new ForeignKeyConstraint("nim", "students", "nim", ForeignKeyAction.CASCADE, ForeignKeyAction.SET_NULL),
                new ForeignKeyConstraint("code", "courses", "code", ForeignKeyAction.NO_ACTION, ForeignKeyAction.NO_ACTION)),
                takes.foreignKeys());

        assertEquals(List.of(new SchemaScript.IndexDefinition(
                new IndexInfo("idx_takes_nim", "takes", "nim", IndexType.BPLUS_TREE), 15)), script.indexes());
    }

    // kolom primary key otomatis NOT NULL walau ga ditulis
    @Test
    void primaryKeyColumnsBecomeNotNull() {
        TableSchema schema = SchemaScriptParser.parse("CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, b));")
                .tables().get(0).schema();

        assertEquals(List.of(false, false), schema.columns().stream().map(ColumnDefinition::nullable).toList());
    }

    // DDL selain CREATE TABLE dan CREATE INDEX ditolak jelas
    @Test
    void rejectsUnsupportedStatements() {
        assertRejected("DROP TABLE students;", "Unsupported statement 'DROP'");
        assertRejected("ALTER TABLE students ADD x INTEGER;", "Unsupported statement 'ALTER'");
        assertRejected("INSERT INTO students VALUES (1);", "Unsupported statement 'INSERT'");
        assertRejected("CREATE VIEW v AS SELECT 1;", "Unsupported statement 'CREATE VIEW'");
    }

    // tipe, panjang, dan constraint yang salah ditolak dengan nomor baris
    @Test
    void rejectsInvalidDefinitionsWithLocation() {
        assertRejected("CREATE TABLE t (\n  a TEXT\n);", "line 2, column 5: Unsupported type 'TEXT'");
        assertRejected("CREATE TABLE t (a VARCHAR);", "needs a length");
        assertRejected("CREATE TABLE t (a VARCHAR(0));", "must be positive");
        assertRejected("CREATE TABLE t (a INTEGER(4));", "does not take a length");
        assertRejected("CREATE TABLE t (a INTEGER NULL PRIMARY KEY);", "cannot be NULL");
        assertRejected("CREATE TABLE t (a INTEGER NULL NOT NULL);", "both NULL and NOT NULL");
        assertRejected("CREATE TABLE t (a INTEGER PRIMARY KEY, PRIMARY KEY (a));", "PRIMARY KEY more than once");
        assertRejected("CREATE TABLE t (a INTEGER, PRIMARY KEY (a, a));", "more than once");
        assertRejected("CREATE INDEX i ON t(a, b);", "exactly one column");
        assertRejected("CREATE TABLE t (a INTEGER", "Unexpected end of script");
        assertRejected("CREATE TABLE t (a INTEGER) CREATE TABLE u (b INTEGER);", "Expected ';'");
        assertRejected("CREATE TABLE t (a INTEGER, b FLOAT DEFAULT 1);", "Unsupported column option 'DEFAULT'");
        assertRejected("CREATE TABLE t (a INTEGER, 'x');", "Unexpected character");
    }
}
