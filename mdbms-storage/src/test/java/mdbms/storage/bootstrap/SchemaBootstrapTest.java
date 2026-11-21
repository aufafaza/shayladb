package mdbms.storage.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.RowId;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.storage.SampleDataLoader;
import mdbms.storage.StorageManager;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaBootstrapTest {

    private static final String SCHEMA = """
            CREATE TABLE students (
              nim INTEGER PRIMARY KEY,
              name CHAR(100) NOT NULL,
              gpa FLOAT
            );
            CREATE TABLE takes (
              id INTEGER PRIMARY KEY,
              nim INTEGER NOT NULL,
              code VARCHAR(30) NOT NULL,
              FOREIGN KEY (nim) REFERENCES students(nim) ON DELETE RESTRICT,
              FOREIGN KEY (code) REFERENCES courses(code)
            );
            CREATE TABLE courses (
              code VARCHAR(30) PRIMARY KEY,
              credits INTEGER NOT NULL
            );
            CREATE INDEX idx_takes_nim ON takes(nim);
            CREATE INDEX idx_students_name ON students(name);
            """;

    @TempDir Path directory;
    private StorageManager storage;

    @BeforeEach
    void setUp() {
        storage = open(directory);
    }

    // StorageManager dengan FRM yang selalu nerima flush, sama kayak test SM lain
    private static StorageManager open(Path directory) {
        StorageManager storage = new StorageManager(directory.toString(), 16, 4);
        storage.attachRecoveryManager((IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null));
        return storage;
    }

    // pastiin bootstrap gagal dengan SCHEMA_VIOLATION dan pesannya nyebut potongan teks tertentu
    private static void assertRejected(StorageManager storage, String source, String expected) {
        StorageException e = assertThrows(StorageException.class, () -> new SchemaBootstrap(storage).apply(source));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, e.getCode());
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    // schema lengkap kebuat, FK boleh nunjuk tabel yang ditulis belakangan, dan semuanya kebaca lagi setelah restart
    @Test
    void createsSchemaThatSurvivesRestart() {
        SchemaBootstrap.Result result = new SchemaBootstrap(storage).apply(SCHEMA);

        assertEquals(List.of("students", "takes", "courses"), result.createdTables());
        assertEquals(List.of("idx_takes_nim", "idx_students_name"), result.createdIndexes());
        assertEquals(0, storage.getBufferStats().dirtyBlocks());

        StorageManager restarted = open(directory);
        assertEquals(List.of("students", "takes", "courses"), restarted.listTable());
        assertEquals(storage.getTableSchema("takes"), restarted.getTableSchema("takes"));
        assertEquals(List.of(new IndexInfo("idx_takes_nim", "takes", "nim", IndexType.BPLUS_TREE)),
                restarted.getIndexes("takes"));

        Map<String, Object> student = new LinkedHashMap<>();
        student.put("nim", 1);
        student.put("name", "alice");
        student.put("gpa", 3.5f);
        RowId alice = restarted.writeBlock(new DataWrite("students", student, List.of())).get(0);
        assertEquals(List.of(alice), restarted.searchIndex("idx_students_name", "alice"));
    }

    // jalanin ulang skrip yang sama ga ngubah apa apa, cocok buat dipanggil tiap deploy
    @Test
    void rerunningSameScriptIsANoOp() {
        new SchemaBootstrap(storage).apply(SCHEMA);

        SchemaBootstrap.Result again = new SchemaBootstrap(open(directory)).apply(SCHEMA);

        assertTrue(again.createdTables().isEmpty());
        assertTrue(again.createdIndexes().isEmpty());
        assertEquals(List.of("students", "takes", "courses"), again.skippedTables());
        assertEquals(List.of("idx_takes_nim", "idx_students_name"), again.skippedIndexes());
    }

    // tabel yang udah ada tapi definisinya beda ditolak, katalog lama ga kesentuh
    @Test
    void rejectsConflictingExistingDefinitions() {
        new SchemaBootstrap(storage).apply(SCHEMA);

        assertRejected(storage, "CREATE TABLE students (nim INTEGER PRIMARY KEY);", "different definition");
        assertRejected(storage, "CREATE INDEX idx_takes_nim ON takes(code);", "different definition");
        assertEquals(3, storage.listTable().size());
    }

    // definisi salah ditolak sebelum ada yang ditulis, jadi katalog tetap kosong
    @Test
    void invalidScriptsWriteNothing() {
        assertRejected(storage, "CREATE TABLE a (x INTEGER PRIMARY KEY); CREATE TABLE a (y INTEGER PRIMARY KEY);",
                "already defined on line 1");
        assertRejected(storage, "CREATE TABLE a (x INTEGER, PRIMARY KEY (y));", "PRIMARY KEY column y does not exist");
        assertRejected(storage, "CREATE TABLE a (x INTEGER, x FLOAT, PRIMARY KEY (x));", "declared twice");
        assertRejected(storage, "CREATE TABLE a (x INTEGER PRIMARY KEY, FOREIGN KEY (z) REFERENCES a(x));",
                "column z does not exist");
        assertRejected(storage, "CREATE TABLE a (x INTEGER PRIMARY KEY, FOREIGN KEY (x) REFERENCES ghost(id));",
                "unknown table ghost");
        assertRejected(storage, "CREATE TABLE a (x INTEGER PRIMARY KEY, FOREIGN KEY (x) REFERENCES a(nope));",
                "unknown column a.nope");
        assertRejected(storage, """
                CREATE TABLE a (x INTEGER PRIMARY KEY);
                CREATE TABLE b (y VARCHAR(5) PRIMARY KEY, FOREIGN KEY (y) REFERENCES a(x));
                """, "is VARCHAR but a.x is INTEGER");
        assertRejected(storage, "CREATE TABLE a (x INTEGER PRIMARY KEY); CREATE INDEX i ON ghost(x);",
                "unknown table ghost");
        assertRejected(storage, "CREATE TABLE a (x INTEGER PRIMARY KEY); CREATE INDEX i ON a(nope);",
                "unknown column a.nope");
        assertRejected(storage, "CREATE TABLE a (x INTEGER PRIMARY KEY); CREATE INDEX i ON a(x); CREATE INDEX i ON a(x);",
                "already defined on line 1");

        assertTrue(storage.listTable().isEmpty());
        assertTrue(new StorageManager(directory.toString(), 16, 4).listTable().isEmpty());
    }

    // schema/sample.sql harus sama persis dengan tabel SM14 di SampleDataLoader biar ga ada dua sumber schema yang beda
    @Test
    void sampleScriptMatchesSampleDataLoader(@TempDir Path loaded) throws Exception {
        new SchemaBootstrap(storage).apply(Files.readString(Path.of("..", "schema", "sample.sql")));
        StorageManager reference = open(loaded);
        new SampleDataLoader(reference, reference).loadIfEmpty();

        assertEquals(Set.copyOf(reference.listTable()), Set.copyOf(storage.listTable()));
        for (String table : reference.listTable()) {
            assertEquals(reference.getTableSchema(table), storage.getTableSchema(table), table);
        }
    }

    // gagal di tengah penerapan bikin tabel dan index dari run ini dihapus lagi, tabel lama tetap utuh
    @Test
    void failureWhileApplyingRollsBackThisRun() throws Exception {
        new SchemaBootstrap(storage).apply("CREATE TABLE keep (id INTEGER PRIMARY KEY);");
        Files.createDirectory(directory.resolve("idx_b_y.idx"));

        StorageException failure = assertThrows(StorageException.class, () -> new SchemaBootstrap(storage).apply("""
                CREATE TABLE a (x INTEGER PRIMARY KEY);
                CREATE TABLE b (y INTEGER PRIMARY KEY);
                CREATE INDEX idx_a_x ON a(x);
                CREATE INDEX idx_b_y ON b(y);
                """));

        assertTrue(failure.getCode() != StorageErrorCode.SCHEMA_VIOLATION, failure.getMessage());
        assertEquals(List.of("keep"), storage.listTable());
        StorageManager restarted = open(directory);
        assertEquals(List.of("keep"), restarted.listTable());
        assertNull(restarted.getTableSchema("a"));
        assertTrue(restarted.readBlock(new DataRetrieval("keep", List.of(), List.of())).rows().isEmpty());
    }
}
