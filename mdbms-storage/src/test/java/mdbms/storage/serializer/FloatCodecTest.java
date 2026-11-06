package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import org.junit.jupiter.api.Test;

class FloatCodecTest {
    private final FloatCodec codec = new FloatCodec();
    private final ColumnDefinition column = new ColumnDefinition("value", DataType.FLOAT, 0, false);

    // memastikan pola bit float tetap sama setelah dibaca ulang
    @Test
    void preservesRawFloatBits() {
        for (int bits : new int[] {0, 0x80000000, 0x7f800000, 0x7fc00001, 0x3f800000}) {
            Float value = Float.intBitsToFloat(bits);
            Float decoded = (Float) codec.decode(codec.encode(value, column), column);
            assertEquals(bits, Float.floatToRawIntBits(decoded));
        }
    }

    // memastikan double dan data kepotong ditolak
    @Test
    void rejectsDoubleAndTruncation() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(1.0d, column));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[5], column));
    }
}
