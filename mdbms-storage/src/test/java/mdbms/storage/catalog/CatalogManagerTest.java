package mdbms.storage.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

// test CatalogManager SM4, simpan schema dan metadata index ke catalog.bin lalu baca lagi
class CatalogManagerTest {

    private static final TableSchema STUDENTS = new TableSchema("students", List.of(
            new ColumnDefinition("nim", DataType.INTEGER, 0, false),
            new ColumnDefinition("name", DataType.VARCHAR, 50, false),
            new ColumnDefinition("gpa", DataType.FLOAT, 0, true)),
            List.of("nim"), List.of());

    private static final TableSchema TAKES = new TableSchema("takes", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 0, false),
            new ColumnDefinition("nim", DataType.INTEGER, 0, false),
            new ColumnDefinition("grade", DataType.CHAR, 2, true)),
            List.of("id"), List.of(new ForeignKeyConstraint("nim", "students", "nim",
                    ForeignKeyAction.CASCADE, ForeignKeyAction.RESTRICT)));

    private static final IndexInfo IDX_NIM = new IndexInfo("idx_students_nim", "students", "nim", IndexType.BPLUS_TREE);

    @TempDir Path directory;
    private CatalogManager catalog;

    @BeforeEach
    void setUp() {
        catalog = new CatalogManager(directory.toString());
    }

    // pastiin aksi gagal dengan StorageException kode tertentu
    private static void assertCode(StorageErrorCode code, Executable action) {
        assertEquals(code, assertThrows(StorageException.class, action).getCode());
    }

    // katalog baru kosong dan belum bikin file apa pun
    @Test
    void newCatalogIsEmpty() {
        assertTrue(catalog.listTables().isEmpty());
        assertTrue(catalog.getAllIndexes().isEmpty());
        assertFalse(Files.exists(directory.resolve("catalog.bin")));
    }

    // schema dan index yang disimpan kebaca lagi sama persis setelah katalog dibuka ulang
    @Test
    void schemaAndIndexSurviveReopen() {
        catalog.saveSchema(STUDENTS);
        catalog.saveSchema(TAKES);
        catalog.registerIndex(IDX_NIM);

        CatalogManager reopened = new CatalogManager(directory.toString());

        assertEquals(List.of("students", "takes"), reopened.listTables());
        assertEquals(STUDENTS, reopened.getSchema("students"));
        assertEquals(TAKES, reopened.getSchema("takes"));
        assertEquals(List.of(IDX_NIM), reopened.getIndexes("students"));
        assertTrue(reopened.getIndexes("takes").isEmpty());
    }

    // saveSchema dengan nama yang sama mengganti schema lama
    @Test
    void saveSchemaReplacesExistingTable() {
        catalog.saveSchema(STUDENTS);
        TableSchema changed = new TableSchema("students", List.of(
                new ColumnDefinition("nim", DataType.INTEGER, 0, false)), List.of("nim"), List.of());

        catalog.saveSchema(changed);

        assertEquals(changed, catalog.getSchema("students"));
        assertEquals(1, catalog.listTables().size());
    }

    // drop tabel ikut ngapus index nya dan tetap kebawa setelah dibuka ulang
    @Test
    void dropTableRemovesItsIndexes() {
        catalog.saveSchema(STUDENTS);
        catalog.registerIndex(IDX_NIM);

        catalog.dropTable("students");

        assertFalse(catalog.tableExists("students"));
        assertTrue(catalog.getAllIndexes().isEmpty());
        CatalogManager reopened = new CatalogManager(directory.toString());
        assertTrue(reopened.listTables().isEmpty());
        assertTrue(reopened.getAllIndexes().isEmpty());
    }

    // register dan unregister index nolak tabel, kolom, atau nama yang salah
    @Test
    void indexRegistrationErrors() {
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> catalog.registerIndex(IDX_NIM));
        catalog.saveSchema(STUDENTS);
        assertCode(StorageErrorCode.COLUMN_NOT_FOUND, () -> catalog.registerIndex(
                new IndexInfo("idx_age", "students", "age", IndexType.BPLUS_TREE)));
        catalog.registerIndex(IDX_NIM);
        assertCode(StorageErrorCode.INDEX_ALREADY_EXISTS, () -> catalog.registerIndex(IDX_NIM));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.registerIndex(null));

        catalog.unregisterIndex("idx_students_nim");
        assertTrue(catalog.getIndexes("students").isEmpty());
        assertCode(StorageErrorCode.INDEX_NOT_FOUND, () -> catalog.unregisterIndex("idx_students_nim"));
    }

    // tabel yang ga ada kasih TABLE_NOT_FOUND
    @Test
    void missingTableErrors() {
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> catalog.getSchema("ghost"));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> catalog.dropTable("ghost"));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> catalog.getIndexes("ghost"));
    }

    // schema yang ga lengkap atau kolomnya bermasalah ditolak dan ga masuk katalog
    @Test
    void invalidSchemaIsRejected() {
        ColumnDefinition id = new ColumnDefinition("id", DataType.INTEGER, 0, false);
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.saveSchema(null));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.saveSchema(
                new TableSchema(" ", List.of(id), List.of(), List.of())));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.saveSchema(
                new TableSchema("t", List.of(), List.of(), List.of())));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.saveSchema(
                new TableSchema("t", List.of(id, id), List.of(), List.of())));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.saveSchema(
                new TableSchema("t", List.of(new ColumnDefinition("s", DataType.VARCHAR, 0, false)), List.of(), List.of())));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.saveSchema(
                new TableSchema("t", List.of(id), List.of("other"), List.of())));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> catalog.saveSchema(
                new TableSchema("t", List.of(id), List.of("id"), List.of(new ForeignKeyConstraint(
                        "other", "x", "y", ForeignKeyAction.CASCADE, ForeignKeyAction.CASCADE)))));

        assertTrue(catalog.listTables().isEmpty());
    }

    // file katalog rusak bikin IO_FAILURE, bukan katalog setengah kebaca
    @Test
    void corruptCatalogFileFails() throws Exception {
        Files.write(directory.resolve("catalog.bin"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        assertCode(StorageErrorCode.IO_FAILURE, () -> new CatalogManager(directory.toString()));
    }

    // load ulang setelah file katalog dihapus bikin isi katalog di memori ikut kosong
    @Test
    void reloadWithoutFileClearsCatalog() throws Exception {
        catalog.saveSchema(STUDENTS);
        catalog.registerIndex(IDX_NIM);
        Files.delete(directory.resolve("catalog.bin"));

        catalog.load();

        assertTrue(catalog.listTables().isEmpty());
        assertTrue(catalog.getAllIndexes().isEmpty());
    }

    // direktori kosong ditolak dari awal
    @Test
    void blankDirectoryIsRejected() {
        assertCode(StorageErrorCode.IO_FAILURE, () -> new CatalogManager(" "));
        assertCode(StorageErrorCode.IO_FAILURE, () -> new CatalogManager(null));
    }
}
