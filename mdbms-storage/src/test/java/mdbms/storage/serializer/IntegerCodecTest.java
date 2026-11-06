package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import org.junit.jupiter.api.Test;

class IntegerCodecTest {
    private final IntegerCodec codec = new IntegerCodec();
    private final ColumnDefinition column = new ColumnDefinition("id", DataType.INTEGER, 0, false);

    // memastikan batas integer dan urutan byte tetap benar
    @Test
    void preservesSignedBoundsAndByteOrder() {
        assertArrayEquals(new byte[] {(byte) 0x80, 0, 0, 0}, codec.encode(Integer.MIN_VALUE, column));
        assertEquals(Integer.MIN_VALUE, codec.decode(codec.encode(Integer.MIN_VALUE, column), column));
        assertEquals(Integer.MAX_VALUE, codec.decode(codec.encode(Integer.MAX_VALUE, column), column));
    }

    // memastikan tipe dan ukuran byte yang salah ditolak
    @Test
    void rejectsWrongTypeAndSize() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(1L, column));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[3], column));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(1, new ColumnDefinition("bad", DataType.FLOAT, 0, false)));
    }
}
