package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import mdbms.storage.serializer.IntegerCodec;
import mdbms.storage.serializer.VarcharCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// test BPlusTreeIndex di atas BufferPool asli dengan buffer kecil biar eviction ikut keuji
class BPlusTreeIndexTest {

    private static final ColumnDefinition ID = new ColumnDefinition("id", DataType.INTEGER, 0, false);
    private static final ColumnDefinition NAME = new ColumnDefinition("name", DataType.VARCHAR, 10, false);
    private static final IndexInfo ID_INFO = new IndexInfo("idx_users_id", "users", "id", IndexType.BPLUS_TREE);

    @TempDir Path directory;
    private BufferPool pool;

    // buffer 8 frame dan FRM palsu yang selalu bilang log udah aman
    @BeforeEach
    void setUp() {
        pool = new BufferPool(new BlockManager(directory.toString()), 8);
        pool.attachRecoveryManager((IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null));
    }

    // index INTEGER baru yang udah di create
    private BPlusTreeIndex intIndex(int maxDegree) {
        BPlusTreeIndex index = openIntIndex(maxDegree);
        index.create();
        return index;
    }

    // buka index INTEGER tanpa create, dipakai buat ngecek file lama kebaca lagi
    private BPlusTreeIndex openIntIndex(int maxDegree) {
        return new BPlusTreeIndex(ID_INFO, ID, maxDegree, pool, new KeyNormalizer(), new NodeCodec(ID, new IntegerCodec()));
    }

    // RowId tabel users di slot tertentu
    private static RowId row(int slot) {
        return new RowId("users", slot / 50, slot % 50);
    }

    // urutin RowId biar hasil search bisa dibandingin tanpa peduli urutan bucket
    private static List<RowId> sorted(List<RowId> rowIds) {
        List<RowId> copy = new ArrayList<>(rowIds);
        copy.sort((a, b) -> a.blockNumber() != b.blockNumber()
                ? Integer.compare(a.blockNumber(), b.blockNumber()) : Integer.compare(a.slot(), b.slot()));
        return copy;
    }

    // RowId yang diharapkan dari range di TreeMap pembanding
    private static List<RowId> expectedRange(NavigableMap<Integer, List<RowId>> model, Integer low, Integer high,
            boolean lowInclusive, boolean highInclusive) {
        NavigableMap<Integer, List<RowId>> view = model;
        if (low != null) view = view.tailMap(low, lowInclusive);
        if (high != null) view = view.headMap(high, highInclusive);
        List<RowId> result = new ArrayList<>();
        view.values().forEach(result::addAll);
        return result;
    }

    // degree maksimal dihitung dari ukuran key, kolom kelebaran ditolak
    @Test
    void computeMaxDegreeFollowsKeySize() {
        assertEquals(511, BPlusTreeIndex.computeMaxDegree(ID));
        assertEquals(256, BPlusTreeIndex.computeMaxDegree(NAME));
        assertThrows(StorageException.class,
                () -> BPlusTreeIndex.computeMaxDegree(new ColumnDefinition("bio", DataType.VARCHAR, 3000, true)));
    }

    // degree di bawah 3 atau di atas batas ditolak
    @Test
    void rejectsInvalidMaxDegree() {
        StorageException e = assertThrows(StorageException.class, () -> openIntIndex(2));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, e.getCode());
        assertThrows(StorageException.class, () -> openIntIndex(512));
    }

    // index baru kosong dan tingginya 1
    @Test
    void newIndexIsEmpty() {
        BPlusTreeIndex index = intIndex(4);

        assertEquals(1, index.getHeight());
        assertTrue(index.search(1).isEmpty());
        assertTrue(index.searchRange(null, null, true, true).isEmpty());
    }

    // ratusan key acak dengan degree 4 bikin banyak split dan semuanya tetap ketemu
    @Test
    void insertManyKeysWithSplits() {
        BPlusTreeIndex index = intIndex(4);
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) keys.add(i);
        Collections.shuffle(keys, new Random(42));

        for (int key : keys) index.insert(key, row(key));

        assertTrue(index.getHeight() >= 4);
        for (int key = 0; key < 300; key++) {
            assertEquals(List.of(row(key)), index.search(key));
        }
        assertTrue(index.search(300).isEmpty());
        assertTrue(index.search(-1).isEmpty());
    }

    // range dengan semua kombinasi batas sama persis dengan TreeMap pembanding
    @Test
    void searchRangeMatchesTreeMap() {
        BPlusTreeIndex index = intIndex(5);
        NavigableMap<Integer, List<RowId>> model = new TreeMap<>();
        Random random = new Random(7);
        for (int i = 0; i < 400; i++) {
            int key = random.nextInt(150) * 2;
            RowId rowId = row(i);
            index.insert(key, rowId);
            model.computeIfAbsent(key, k -> new ArrayList<>()).add(rowId);
        }

        Integer[][] bounds = {{null, null}, {null, 100}, {100, null}, {40, 41}, {41, 43}, {0, 298}, {-10, 5}, {297, 500}};
        for (Integer[] bound : bounds) {
            for (boolean lowInclusive : new boolean[] {true, false}) {
                for (boolean highInclusive : new boolean[] {true, false}) {
                    assertEquals(sorted(expectedRange(model, bound[0], bound[1], lowInclusive, highInclusive)),
                            sorted(index.searchRange(bound[0], bound[1], lowInclusive, highInclusive)),
                            "range " + bound[0] + " " + bound[1] + " " + lowInclusive + " " + highInclusive);
                }
            }
        }
        assertTrue(index.searchRange(50, 10, true, true).isEmpty());
    }

    // hasil range urut naik berdasarkan key
    @Test
    void searchRangeIsOrderedByKey() {
        BPlusTreeIndex index = intIndex(4);
        for (int key = 50; key >= 1; key--) index.insert(key, row(key));

        List<RowId> result = index.searchRange(10, 20, true, true);

        List<RowId> expected = new ArrayList<>();
        for (int key = 10; key <= 20; key++) expected.add(row(key));
        assertEquals(expected, result);
    }

    // satu key dengan 1200 RowId nyambung ke beberapa bucket dan pasangan dobel diabaikan
    @Test
    void duplicateKeysSpanBucketChain() {
        BPlusTreeIndex index = intIndex(4);
        for (int i = 0; i < 1200; i++) index.insert(7, row(i));
        index.insert(7, row(0));
        index.insert(8, row(5000));

        List<RowId> found = index.search(7);

        assertEquals(1200, found.size());
        assertEquals(1200, new HashSet<>(found).size());
        assertEquals(List.of(row(5000)), index.search(8));
    }

    // key mentah dinormalisasi dulu dan key null ga diindex
    @Test
    void normalizesKeysAndSkipsNull() {
        BPlusTreeIndex index = intIndex(4);
        index.insert(5L, row(1));
        index.insert("5", row(2));
        index.insert(null, row(3));

        assertEquals(sorted(List.of(row(1), row(2))), sorted(index.search(5)));
        assertTrue(index.search(null).isEmpty());
    }

    // key VARCHAR urut sesuai String dan bisa di range
    @Test
    void varcharKeys() {
        IndexInfo info = new IndexInfo("idx_users_name", "users", "name", IndexType.BPLUS_TREE);
        BPlusTreeIndex index = new BPlusTreeIndex(info, NAME, 3, pool, new KeyNormalizer(), new NodeCodec(NAME, new VarcharCodec()));
        index.create();
        String[] names = {"dodi", "alice", "eka", "bob", "cici", "fajar", "gita"};
        for (int i = 0; i < names.length; i++) index.insert(names[i], row(i));

        assertEquals(List.of(row(1)), index.search("alice"));
        assertEquals(List.of(row(3), row(4), row(0)), index.searchRange("b", "e", true, false));
    }

    // index yang dibuka ulang dari file yang sama tetap bisa dicari setelah flush
    @Test
    void reopenedIndexKeepsData() {
        BPlusTreeIndex index = intIndex(4);
        for (int key = 0; key < 100; key++) index.insert(key, row(key));
        pool.flushBuffer();

        BPlusTreeIndex reopened = openIntIndex(6);

        assertEquals(4, reopened.getMaxDegree());
        assertEquals(index.getHeight(), reopened.getHeight());
        assertEquals(List.of(row(42)), reopened.search(42));
        assertEquals(100, reopened.searchRange(null, null, true, true).size());
    }

    // create ulang ngosongin index lama
    @Test
    void createAgainResetsIndex() {
        BPlusTreeIndex index = intIndex(4);
        for (int key = 0; key < 50; key++) index.insert(key, row(key));

        index.create();

        assertEquals(1, index.getHeight());
        assertTrue(index.searchRange(null, null, true, true).isEmpty());
        index.insert(1, row(1));
        assertEquals(List.of(row(1)), index.search(1));
    }

    // RowId tabel lain ditolak sebelum ada block yang dialokasiin
    @Test
    void rowIdOfOtherTableIsRejected() {
        BPlusTreeIndex index = intIndex(4);

        StorageException e = assertThrows(StorageException.class, () -> index.insert(1, new RowId("orders", 0, 0)));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, e.getCode());
        assertEquals(2, pool.blockCount("idx_users_id.idx"));
    }
}
