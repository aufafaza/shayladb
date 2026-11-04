package mdbms.storage.index;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;

/**
 * Mengubah nilai key mentah ke bentuk kanonik sesuai tipe kolom,
 * agar nilai SQL yang sama menjadi key B+Tree yang sama (mis. 1, 1L, "1").
 * INTEGER -> Integer, FLOAT -> Double, CHAR/VARCHAR -> String.
 */
public final class KeyNormalizer {

    /** Normalisasi key untuk kolom; null tetap null (tidak diindeks). */
    public Object normalize(Object key, ColumnDefinition column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        if (key == null) {
            return null;
        }
        return switch (column.type()) {
            case INTEGER -> toInteger(key);
            case FLOAT -> toDouble(key);
            case CHAR -> toCharKey(key, column.length());
            case VARCHAR -> toVarcharKey(key, column.length());
        };
    }

    /** Ke Integer; tolak pecahan, overflow, dan teks non-angka. */
    private Integer toInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long || value instanceof Short || value instanceof Byte) {
            long l = ((Number) value).longValue();
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw mismatch(value, DataType.INTEGER, "out of int range");
            }
            return (int) l;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isInfinite(d) || d != Math.rint(d)
                    || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw mismatch(value, DataType.INTEGER, "not a whole number in int range");
            }
            return (int) d;
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                throw mismatch(value, DataType.INTEGER, "not a valid integer");
            }
        }
        throw mismatch(value, DataType.INTEGER, "unsupported value type");
    }

    /** Ke Double; tolak NaN/Infinity, -0.0 disamakan dengan 0.0. */
    private Double toDouble(Object value) {
        double d;
        if (value instanceof Number n) {
            d = n.doubleValue();
        } else if (value instanceof String s) {
            try {
                d = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw mismatch(value, DataType.FLOAT, "not a valid number");
            }
        } else {
            throw mismatch(value, DataType.FLOAT, "unsupported value type");
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw mismatch(value, DataType.FLOAT, "NaN/Infinity cannot be indexed");
        }
        return d == 0.0 ? 0.0 : d;
    }

    /** CHAR(n): buang spasi di belakang, lalu cek panjang maksimal n. */
    private String toCharKey(Object value, int length) {
        String s = stripTrailingSpaces(asString(value, DataType.CHAR));
        checkLength(s, length, DataType.CHAR, value);
        return s;
    }

    /** VARCHAR(n): tanpa trim, hanya cek panjang maksimal n. */
    private String toVarcharKey(Object value, int length) {
        String s = asString(value, DataType.VARCHAR);
        checkLength(s, length, DataType.VARCHAR, value);
        return s;
    }

    /** Terima String atau Character saja. */
    private static String asString(Object value, DataType type) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Character c) {
            return String.valueOf(c);
        }
        throw mismatch(value, type, "unsupported value type");
    }

    /** Hapus spasi ' ' di akhir string (semantik padding CHAR). */
    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /** Cek panjang string; length <= 0 berarti tanpa batas. */
    private static void checkLength(String s, int length, DataType type, Object original) {
        if (length > 0 && s.length() > length) {
            throw mismatch(original, type, "longer than " + length + " characters");
        }
    }

    /** Buat exception untuk nilai yang tidak cocok dengan tipe kolom. */
    private static IllegalArgumentException mismatch(Object value, DataType type, String reason) {
        return new IllegalArgumentException(
                "Cannot use " + value.getClass().getSimpleName() + " '" + value
                        + "' as " + type + " index key: " + reason);
    }
}
