package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import org.junit.jupiter.api.Test;

class VarcharCodecTest {
    private final VarcharCodec codec = new VarcharCodec();
    private final ColumnDefinition column = new ColumnDefinition("name", DataType.VARCHAR, 4, false);

    // memastikan panjang byte utf-8 disimpan di depan teks
    @Test
    void prefixesUtf8ByteCount() {
        assertArrayEquals(new byte[] {0, 4, (byte) 0xc3, (byte) 0xa9, (byte) 0xc3, (byte) 0xa9},
                codec.encode("éé", column));
        assertEquals("éé", codec.decode(codec.encode("éé", column), column));
        assertEquals("", codec.decode(codec.encode("", column), column));
        assertThrows(IllegalArgumentException.class, () -> codec.encode("ééé", column));
    }

    // memastikan panjang dan isi utf-8 yang rusak ditolak
    @Test
    void rejectsCorruptPrefixAndUtf8() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {0, 2, 'a'}, column));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {0, 1, (byte) 0xff}, column));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {0, 0, 1}, column));
    }
}
