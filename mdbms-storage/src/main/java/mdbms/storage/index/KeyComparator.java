package mdbms.storage.index;

import java.util.Comparator;
import java.util.Objects;
import mdbms.models.schema.DataType;

// pembanding key index per tipe kolom, key wajib udah dinormalisasi KeyNormalizer dan ga boleh null
public final class KeyComparator implements Comparator<Object> {

    private final DataType type;

    // bikin comparator buat satu tipe kolom
    public KeyComparator(DataType type) {
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    // bandingin dua key, string diurutin per karakter jadi huruf besar duluan dibanding huruf kecil
    @Override
    public int compare(Object a, Object b) {
        Objects.requireNonNull(a, "index key must not be null");
        Objects.requireNonNull(b, "index key must not be null");
        return switch (type) {
            case INTEGER -> Integer.compare(cast(a, Integer.class), cast(b, Integer.class));
            case FLOAT -> Float.compare(cast(a, Float.class), cast(b, Float.class));
            case CHAR, VARCHAR -> cast(a, String.class).compareTo(cast(b, String.class));
        };
    }

    // tolak key yang belum dinormalisasi biar ga diam diam salah urut
    private <T> T cast(Object value, Class<T> expected) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException(
                    "Expected normalized " + expected.getSimpleName() + " key for " + type
                            + " but got " + value.getClass().getSimpleName() + " '" + value + "'");
        }
        return expected.cast(value);
    }
}
