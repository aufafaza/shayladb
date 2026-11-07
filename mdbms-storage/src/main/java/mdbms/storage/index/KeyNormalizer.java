package mdbms.storage.index;

import java.nio.charset.StandardCharsets;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

// ubah key mentah jadi satu bentuk baku per tipe kolom, biar angka 1 dari Integer, Long, atau String jadi key yang sama
// INTEGER jadi Integer, FLOAT jadi Float, CHAR dan VARCHAR jadi String
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
            case FLOAT -> toFloat(key);
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

    // ubah ke Float biar sama persis dengan FloatCodec SM3, jadi 0.1 dari query cocok dengan 0.1 yang disimpan
    // NaN, Infinity, dan angka di luar jangkauan Float ditolak, nol negatif disamain jadi nol biasa
    private Float toFloat(Object value) {
        float f;
        if (value instanceof Number n) {
            f = n.floatValue();
        } else if (value instanceof String s) {
            try {
                f = Float.parseFloat(s.trim());
            } catch (NumberFormatException e) {
                throw mismatch(value, DataType.FLOAT, "not a valid number");
            }
        } else {
            throw mismatch(value, DataType.FLOAT, "unsupported value type");
        }
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            throw mismatch(value, DataType.FLOAT, "NaN, Infinity, or out of float range");
        }
        return f == 0.0f ? 0.0f : f;
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

    // cek panjang maksimal kolom dalam byte UTF 8 biar sama dengan codec SM3, length 0 artinya ga ada batas
    private static void checkLength(String s, int length, DataType type, Object original) {
        if (length > 0 && s.getBytes(StandardCharsets.UTF_8).length > length) {
            throw mismatch(original, type, "longer than " + length + " bytes");
        }
    }

    // bikin SCHEMA_VIOLATION yang seragam untuk semua penolakan key
    private static StorageException mismatch(Object value, DataType type, String reason) {
        return new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                "Cannot use " + value.getClass().getSimpleName() + " '" + value
                        + "' as " + type + " index key: " + reason);
    }
}
