package mdbms.storage.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.catalog.CatalogManager;
import mdbms.storage.crud.HeapFile;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import mdbms.storage.serializer.BinaryRecordSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// test StatisticsManager SM4, hitung nr br lr fr dan V(A,r) dari isi heap lalu cache sampai di invalidate
class StatisticsManagerTest {

    private static final TableSchema USERS = new TableSchema("users", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 0, false),
            new ColumnDefinition("name", DataType.VARCHAR, 20, false),
            new ColumnDefinition("city", DataType.CHAR, 8, true)),
            List.of("id"), List.of());

    @TempDir Path directory;
    private CatalogManager catalog;
    private StatisticsManager statistics;
    private HeapFile heap;

    @BeforeEach
    void setUp() {
        catalog = new CatalogManager(directory.toString());
        catalog.saveSchema(USERS);
        statistics = new StatisticsManager(catalog);
        BufferPool pool = new BufferPool(new BlockManager(directory.toString()), 8);
        heap = new HeapFile(USERS, pool, new BinaryRecordSerializer());
    }

    // tabel kosong punya nr dan br nol, lr pakai ukuran maksimal dari schema
    @Test
    void emptyTableUsesSchemaSize() {
        Statistic stat = statistics.getStatistics("users", heap);

        int lr = Integer.BYTES + (Integer.BYTES + 20) + 8;
        assertEquals(new Statistic("users", 0, 0, lr, BlockManager.BLOCK_SIZE / lr,
                Map.of("id", 0, "name", 0, "city", 0)), stat);
    }

    // nr, br, lr, fr, dan jumlah nilai unik per kolom dihitung dari baris yang ada, null ga dihitung
    @Test
    void computesFromRows() {
        heap.insert(List.of(1, "ab", "bandung"));
        heap.insert(List.of(2, "ab", "jakarta"));
        heap.insert(Arrays.asList(3, "abcd", null));

        Statistic stat = statistics.getStatistics("users", heap);

        // byte per baris, id 4, name 4 tambah panjang string, city 8 kalau ada
        long total = (4 + 6 + 8) + (4 + 6 + 8) + (4 + 8);
        int lr = (int) ((total + 2) / 3);
        assertEquals(3, stat.nr());
        assertEquals(1, stat.br());
        assertEquals(lr, stat.lr());
        assertEquals(BlockManager.BLOCK_SIZE / lr, stat.fr());
        assertEquals(Map.of("id", 3, "name", 2, "city", 2), stat.distinctValues());
    }

    // hasil di cache sampai di invalidate, setelah itu dihitung ulang dari heap
    @Test
    void cachesUntilInvalidated() {
        heap.insert(List.of(1, "a", "x"));
        Statistic first = statistics.getStatistics("users", heap);

        heap.insert(List.of(2, "b", "y"));
        assertSame(first, statistics.getStatistics("users", heap));

        statistics.invalidate("users");
        Statistic second = statistics.getStatistics("users", heap);
        assertNotSame(first, second);
        assertEquals(2, second.nr());
    }

    // input yang ga valid kasih kode error yang jelas
    @Test
    void invalidInputErrors() {
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION,
                assertThrows(StorageException.class, () -> new StatisticsManager(null)).getCode());
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND,
                assertThrows(StorageException.class, () -> statistics.getStatistics(" ", heap)).getCode());
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND,
                assertThrows(StorageException.class, () -> statistics.getStatistics("users", null)).getCode());
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND,
                assertThrows(StorageException.class, () -> statistics.getStatistics("ghost", heap)).getCode());
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND,
                assertThrows(StorageException.class, () -> statistics.invalidate(null)).getCode());
    }
}
