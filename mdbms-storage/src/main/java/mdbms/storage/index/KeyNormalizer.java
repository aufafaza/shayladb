package mdbms.storage.index;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;

// ubah key mentah jadi satu bentuk baku per tipe kolom, biar angka 1 dari Integer, Long, atau String jadi key yang sama
// INTEGER jadi Integer, FLOAT jadi Double, CHAR dan VARCHAR jadi String
public final class KeyNormalizer {

    // pilih konverter sesuai tipe kolom, key null dibalikin null karena NULL ga diindeks
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

    // ubah ke Integer, Long dicek rangenya dulu biar ga overflow diam diam pas di cast
    // pecahan ditolak biar ga kebulatin, NaN ikut ketolak karena NaN ga pernah sama dengan dirinya sendiri
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

    // ubah ke Double, NaN dan Infinity ditolak karena ga punya urutan yang jelas di B+Tree
    // nol negatif disamain jadi nol biasa karena Double compare nganggep keduanya beda
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

    // CHAR dipadding spasi di SQL, jadi spasi di belakang dibuang dulu baru dicek panjangnya
    private String toCharKey(Object value, int length) {
        String s = stripTrailingSpaces(asString(value, DataType.CHAR));
        checkLength(s, length, DataType.CHAR, value);
        return s;
    }

    // VARCHAR ga di trim, kalau kepanjangan ditolak bukan dipotong biar ga salah cocok
    private String toVarcharKey(Object value, int length) {
        String s = asString(value, DataType.VARCHAR);
        checkLength(s, length, DataType.VARCHAR, value);
        return s;
    }

    // cuma nerima String atau Character, selain itu ditolak
    private static String asString(Object value, DataType type) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Character c) {
            return String.valueOf(c);
        }
        throw mismatch(value, type, "unsupported value type");
    }

    // buang spasi di akhir aja, ga pakai stripTrailing karena itu ikut buang tab dan newline
    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    // cek panjang maksimal kolom, length 0 artinya ga ada batas
    private static void checkLength(String s, int length, DataType type, Object original) {
        if (length > 0 && s.length() > length) {
            throw mismatch(original, type, "longer than " + length + " characters");
        }
    }

    // bikin pesan error yang seragam untuk semua penolakan key
    // TODO ganti jadi StorageException SCHEMA_VIOLATION kalau punya SM1 udah ada, cukup ubah method ini
    private static IllegalArgumentException mismatch(Object value, DataType type, String reason) {
        return new IllegalArgumentException(
                "Cannot use " + value.getClass().getSimpleName() + " '" + value
                        + "' as " + type + " index key: " + reason);
    }
}
