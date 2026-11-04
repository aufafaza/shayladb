package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import org.junit.jupiter.api.Test;

// unit test KeyNormalizer, ngecek konversi tiap tipe dan kasus kasus pinggir
// TODO kalau mismatch udah pakai StorageException, ganti assertThrows IllegalArgumentException
class KeyNormalizerTest {

    private final KeyNormalizer normalizer = new KeyNormalizer();

    private static final ColumnDefinition INT = col(DataType.INTEGER, 0);
    private static final ColumnDefinition FLT = col(DataType.FLOAT, 0);

    // helper bikin definisi kolom
    private static ColumnDefinition col(DataType type, int length) {
        return new ColumnDefinition("c", type, length, true);
    }

    // INTEGER harus nerima bilangan bulat apa aja dan string angka
    @Test
    void integerAcceptsIntegralNumbersAndNumericStrings() {
        assertEquals(Integer.valueOf(42), normalizer.normalize(42, INT));
        assertEquals(Integer.valueOf(42), normalizer.normalize(42L, INT));
        assertEquals(Integer.valueOf(7), normalizer.normalize((short) 7, INT));
        assertEquals(Integer.valueOf(-5), normalizer.normalize(" -5 ", INT));
        assertEquals(Integer.valueOf(3), normalizer.normalize(3.0, INT));
    }

    // angka 1 dalam bentuk Integer, Long, dan String harus jadi key yang sama
    @Test
    void integerEquivalentInputsGiveEqualKeys() {
        Object a = normalizer.normalize(1, INT);
        assertEquals(a, normalizer.normalize(1L, INT));
        assertEquals(a, normalizer.normalize("1", INT));
    }

    // INTEGER harus nolak pecahan, NaN, overflow, dan tipe lain
    @Test
    void integerRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(3.5, INT));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(Double.NaN, INT));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(Long.MAX_VALUE, INT));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("abc", INT));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(true, INT));
    }

    // FLOAT harus ngubah angka dan string angka jadi Double
    @Test
    void floatConvertsNumbersAndStringsToDouble() {
        assertEquals(Double.valueOf(1.0), normalizer.normalize(1, FLT));
        assertEquals(Double.valueOf(2.5), normalizer.normalize(2.5f, FLT));
        assertEquals(Double.valueOf(3.25), normalizer.normalize("3.25", FLT));
    }

    // nol negatif harus disamain jadi nol biasa
    @Test
    void floatFoldsNegativeZero() {
        assertEquals(Double.valueOf(0.0), normalizer.normalize(-0.0, FLT));
    }

    // FLOAT harus nolak NaN, Infinity, dan teks bukan angka
    @Test
    void floatRejectsNanInfinityAndGarbage() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(Double.NaN, FLT));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(Double.POSITIVE_INFINITY, FLT));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("NaN", FLT));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("x1", FLT));
    }

    // CHAR cuma buang spasi di belakang, spasi di depan tetap ada
    @Test
    void charStripsTrailingSpacesOnly() {
        ColumnDefinition c5 = col(DataType.CHAR, 5);
        assertEquals("abc", normalizer.normalize("abc", c5));
        assertEquals("abc", normalizer.normalize("abc  ", c5));
        assertEquals("abc", normalizer.normalize("abc   ", c5));
        assertEquals("  a", normalizer.normalize("  a", c5));
        assertEquals("x", normalizer.normalize('x', c5));
    }

    // CHAR harus nolak string kepanjangan dan nilai yang bukan teks
    @Test
    void charRejectsTooLongAndNonText() {
        ColumnDefinition c5 = col(DataType.CHAR, 5);
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("abcdef", c5));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(42, c5));
    }

    // VARCHAR ga di trim, cuma dicek panjangnya
    @Test
    void varcharKeepsValueAndChecksLength() {
        ColumnDefinition v3 = col(DataType.VARCHAR, 3);
        assertEquals("abc", normalizer.normalize("abc", v3));
        assertEquals("ab ", normalizer.normalize("ab ", v3));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("abcd", v3));
    }

    // panjang kolom 0 artinya ga ada batas
    @Test
    void zeroLengthMeansNoLimit() {
        String longText = "x".repeat(1000);
        assertEquals(longText, normalizer.normalize(longText, col(DataType.VARCHAR, 0)));
    }

    // key null harus tetap null
    @Test
    void nullKeyStaysNull() {
        assertNull(normalizer.normalize(null, INT));
        assertNull(normalizer.normalize(null, col(DataType.VARCHAR, 10)));
    }

    // kolom null harus ditolak
    @Test
    void nullColumnIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(1, null));
    }
}
