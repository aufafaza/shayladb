package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

// unit test KeyNormalizer, ngecek konversi tiap tipe dan kasus kasus pinggir
class KeyNormalizerTest {

    private final KeyNormalizer normalizer = new KeyNormalizer();

    private static final ColumnDefinition INT = col(DataType.INTEGER, 0);
    private static final ColumnDefinition FLT = col(DataType.FLOAT, 0);

    // helper bikin definisi kolom
    private static ColumnDefinition col(DataType type, int length) {
        return new ColumnDefinition("c", type, length, true);
    }

    // key yang ditolak harus jadi StorageException SCHEMA_VIOLATION
    private static void assertRejected(Executable action) {
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, assertThrows(StorageException.class, action).getCode());
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
        assertRejected(() -> normalizer.normalize(3.5, INT));
        assertRejected(() -> normalizer.normalize(Double.NaN, INT));
        assertRejected(() -> normalizer.normalize(Long.MAX_VALUE, INT));
        assertRejected(() -> normalizer.normalize("abc", INT));
        assertRejected(() -> normalizer.normalize(true, INT));
    }

    // FLOAT harus ngubah angka dan string angka jadi Float
    @Test
    void floatConvertsNumbersAndStringsToFloat() {
        assertEquals(Float.valueOf(1.0f), normalizer.normalize(1, FLT));
        assertEquals(Float.valueOf(2.5f), normalizer.normalize(2.5f, FLT));
        assertEquals(Float.valueOf(2.5f), normalizer.normalize(2.5, FLT));
        assertEquals(Float.valueOf(3.25f), normalizer.normalize("3.25", FLT));
    }

    // 0.1 dari query bertipe Double harus jadi key yang sama dengan 0.1 yang disimpan sebagai Float
    @Test
    void floatQueryLiteralMatchesStoredFloat() {
        Object stored = normalizer.normalize(0.1f, FLT);
        assertEquals(stored, normalizer.normalize(0.1, FLT));
        assertEquals(stored, normalizer.normalize("0.1", FLT));
    }

    // nol negatif harus disamain jadi nol biasa
    @Test
    void floatFoldsNegativeZero() {
        assertEquals(Float.valueOf(0.0f), normalizer.normalize(-0.0f, FLT));
        assertEquals(Float.valueOf(0.0f), normalizer.normalize(-0.0, FLT));
    }

    // FLOAT harus nolak NaN, Infinity, angka di luar jangkauan Float, dan teks bukan angka
    @Test
    void floatRejectsNanInfinityOverflowAndGarbage() {
        assertRejected(() -> normalizer.normalize(Float.NaN, FLT));
        assertRejected(() -> normalizer.normalize(Double.NaN, FLT));
        assertRejected(() -> normalizer.normalize(Double.POSITIVE_INFINITY, FLT));
        assertRejected(() -> normalizer.normalize(1e40, FLT));
        assertRejected(() -> normalizer.normalize("1e40", FLT));
        assertRejected(() -> normalizer.normalize("NaN", FLT));
        assertRejected(() -> normalizer.normalize("x1", FLT));
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
        assertRejected(() -> normalizer.normalize("abcdef", c5));
        assertRejected(() -> normalizer.normalize(42, c5));
    }

    // panjang CHAR dan VARCHAR dihitung dalam byte UTF 8 sama kayak codec SM3
    @Test
    void textLengthIsCountedInUtf8Bytes() {
        assertEquals("éé", normalizer.normalize("éé", col(DataType.CHAR, 5)));
        assertRejected(() -> normalizer.normalize("ééé", col(DataType.CHAR, 5)));
        assertRejected(() -> normalizer.normalize("éé", col(DataType.VARCHAR, 3)));
    }

    // VARCHAR ga di trim, cuma dicek panjangnya
    @Test
    void varcharKeepsValueAndChecksLength() {
        ColumnDefinition v3 = col(DataType.VARCHAR, 3);
        assertEquals("abc", normalizer.normalize("abc", v3));
        assertEquals("ab ", normalizer.normalize("ab ", v3));
        assertRejected(() -> normalizer.normalize("abcd", v3));
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
