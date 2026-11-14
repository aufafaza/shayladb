package mdbms.storage.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // nr berubah langsung tiap recordWrite tanpa scan ulang, buktinya V(id) masih nilai scan lama
    @Test
    void recordWriteUpdatesRowCountEagerly() {
        heap.insert(List.of(1, "a", "x"));
        Statistic first = statistics.getStatistics("users", heap);

        heap.insert(List.of(2, "b", "y"));
        statistics.recordWrite("users", 1);
        heap.insert(List.of(3, "c", "z"));
        statistics.recordWrite("users", 1);
        Statistic after = statistics.getStatistics("users", heap);

        assertEquals(3, after.nr());
        assertEquals(1, after.distinctValues().get("id"));
        assertEquals(first.lr(), after.lr());

        statistics.recordWrite("users", -1);
        statistics.recordWrite("users", 0);
        assertEquals(2, statistics.getStatistics("users", heap).nr());
    }

    // br selalu ngikutin jumlah block file walau ga ada recordWrite
    @Test
    void blockCountFollowsHeapFile() {
        heap.insert(List.of(1, "a", "x"));
        assertEquals(1, statistics.getStatistics("users", heap).br());

        for (int id = 2; id <= 400; id++) heap.insert(List.of(id, "name" + id, "kota"));

        Statistic stat = statistics.getStatistics("users", heap);
        assertEquals(heap.blockCount(), stat.br());
        assertTrue(stat.br() > 1);
        assertEquals(1, stat.nr());
    }

    // setelah perubahan sejak scan terakhir mencapai batas, V(A,r) dihitung ulang dari heap
    @Test
    void refreshesDistinctValuesAfterManyChanges() {
        heap.insert(List.of(1, "a", "x"));
        statistics.getStatistics("users", heap);

        for (int id = 2; id <= 11; id++) {
            heap.insert(List.of(id, "n" + id, "c" + id));
            statistics.recordWrite("users", 1);
        }
        Statistic stat = statistics.getStatistics("users", heap);

        assertEquals(11, stat.nr());
        assertEquals(11, stat.distinctValues().get("id"));
    }

    // recordWrite sebelum statistik pernah dihitung diabaikan, nr ga pernah negatif
    @Test
    void recordWriteEdgeCases() {
        statistics.recordWrite("users", 1);
        assertEquals(0, statistics.getStatistics("users", heap).nr());

        statistics.recordWrite("users", -1);
        assertEquals(0, statistics.getStatistics("users", heap).nr());
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND,
                assertThrows(StorageException.class, () -> statistics.recordWrite(" ", 1)).getCode());
    }
}
