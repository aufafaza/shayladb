package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import org.junit.jupiter.api.Test;

// unit test KeyComparator, ngecek urutan tiap tipe dan validasi key
class KeyComparatorTest {

    // urutan Integer harus bener, termasuk nilai paling kecil dan paling besar
    @Test
    void ordersIntegers() {
        KeyComparator cmp = new KeyComparator(DataType.INTEGER);
        assertTrue(cmp.compare(-1, 3) < 0);
        assertTrue(cmp.compare(10, 2) > 0);
        assertEquals(0, cmp.compare(7, 7));
        assertTrue(cmp.compare(Integer.MIN_VALUE, Integer.MAX_VALUE) < 0);
    }

    // urutan Double harus bener
    @Test
    void ordersDoubles() {
        KeyComparator cmp = new KeyComparator(DataType.FLOAT);
        assertTrue(cmp.compare(1.5, 2.0) < 0);
        assertTrue(cmp.compare(-0.5, -1.0) > 0);
        assertEquals(0, cmp.compare(3.25, 3.25));
    }

    // CHAR dan VARCHAR diurutin per karakter, huruf besar duluan
    @Test
    void ordersStringsLexicographically() {
        for (DataType type : List.of(DataType.CHAR, DataType.VARCHAR)) {
            KeyComparator cmp = new KeyComparator(type);
            assertTrue(cmp.compare("apple", "banana") < 0);
            assertTrue(cmp.compare("a", "ab") < 0);
            assertTrue(cmp.compare("B", "a") < 0);
            assertEquals(0, cmp.compare("x", "x"));
        }
    }

    // comparator harus bisa dipakai buat sort list
    @Test
    void sortsListConsistently() {
        List<Object> keys = new ArrayList<>(List.of(5, -1, 3, 0));
        keys.sort(new KeyComparator(DataType.INTEGER));
        assertEquals(List.of(-1, 0, 3, 5), keys);
    }

    // key null harus ditolak
    @Test
    void rejectsNullKeys() {
        KeyComparator cmp = new KeyComparator(DataType.INTEGER);
        assertThrows(NullPointerException.class, () -> cmp.compare(null, 1));
        assertThrows(NullPointerException.class, () -> cmp.compare(1, null));
    }

    // key yang belum dinormalisasi harus ditolak
    @Test
    void rejectsUnnormalizedKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new KeyComparator(DataType.INTEGER).compare(1L, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new KeyComparator(DataType.FLOAT).compare(1, 2.0));
        assertThrows(IllegalArgumentException.class,
                () -> new KeyComparator(DataType.VARCHAR).compare(1, "a"));
    }

    // tipe null harus ditolak di konstruktor
    @Test
    void rejectsNullType() {
        assertThrows(NullPointerException.class, () -> new KeyComparator(null));
    }

    // hasil KeyNormalizer harus bisa langsung dibandingin
    @Test
    void worksWithNormalizerOutput() {
        KeyNormalizer normalizer = new KeyNormalizer();
        ColumnDefinition col = new ColumnDefinition("id", DataType.INTEGER, 0, false);
        KeyComparator cmp = new KeyComparator(DataType.INTEGER);
        Object a = normalizer.normalize(1L, col);
        Object b = normalizer.normalize("1", col);
        Object c = normalizer.normalize(2.0, col);
        assertEquals(0, cmp.compare(a, b));
        assertTrue(cmp.compare(a, c) < 0);
    }
}
