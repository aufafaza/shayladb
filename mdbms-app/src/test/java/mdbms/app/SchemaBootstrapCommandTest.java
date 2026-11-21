package mdbms.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaBootstrapCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    // jalanin perintah bootstrap dengan output ditampung buat dicek
    private int run(String... args) {
        return SchemaBootstrapCommand.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    // contoh schema di repo bisa dipasang dan kebaca DBMS yang dinyalain setelahnya
    @Test
    void appliesSampleSchemaToDataDirectory(@TempDir Path data) {
        Path sample = Path.of("..", "schema", "sample.sql");

        assertEquals(0, run("bootstrap", sample.toString(), "--data-dir", data.toString()), err.toString());

        StorageManager storage = new StorageManager(data.toString(), 8, 4);
        assertEquals(List.of("students", "courses", "takes"), storage.listTable());
        assertEquals(2, storage.getIndexes("takes").size());
        assertTrue(out.toString().contains("created tables:  [students, courses, takes]"));
    }

    // dijalanin dua kali tetap sukses, run kedua cuma ngelaporin yang udah ada
    @Test
    void secondRunReportsExistingSchema(@TempDir Path data) {
        Path sample = Path.of("..", "schema", "sample.sql");
        run("bootstrap", sample.toString(), "--data-dir", data.toString());
        out.reset();

        assertEquals(0, run("bootstrap", sample.toString(), "--data-dir", data.toString()));
        assertTrue(out.toString().contains("existing tables: [students, courses, takes]"));
    }

    // skrip salah bikin exit code 1 dengan pesan yang nunjuk baris, data directory ga keisi tabel
    @Test
    void invalidScriptFailsWithLineNumber(@TempDir Path data, @TempDir Path scripts) throws Exception {
        Path script = scripts.resolve("bad.sql");
        Files.writeString(script, "CREATE TABLE a (x INTEGER PRIMARY KEY);\nDROP TABLE a;\n");

        assertEquals(1, run("bootstrap", script.toString(), "--data-dir", data.toString()));

        assertTrue(err.toString().contains("line 2"), err.toString());
        assertTrue(new StorageManager(data.toString(), 8, 4).listTable().isEmpty());
    }

    // argumen kurang atau file ga ada dilaporin dengan cara pakai
    @Test
    void reportsUsageProblems(@TempDir Path data) {
        assertEquals(1, run("bootstrap"));
        assertTrue(err.toString().contains("Usage"));
        assertEquals(1, run("bootstrap", data.resolve("missing.sql").toString()));
        assertTrue(err.toString().contains("Cannot read schema file"));
    }
}
