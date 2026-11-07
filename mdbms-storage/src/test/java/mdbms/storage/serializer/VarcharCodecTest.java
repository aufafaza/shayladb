package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
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
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION,
                assertThrows(StorageException.class, () -> codec.encode("ééé", column)).getCode());
    }

    // memastikan panjang dan isi utf-8 yang rusak ditolak
    @Test
    void rejectsCorruptPrefixAndUtf8() {
        assertEquals(StorageErrorCode.INVALID_BLOCK,
                assertThrows(StorageException.class,
                        () -> codec.decode(new byte[] {0, 2, 'a'}, column)).getCode());
        assertThrows(StorageException.class, () -> codec.decode(new byte[] {0, 1, (byte) 0xff}, column));
        assertThrows(StorageException.class, () -> codec.decode(new byte[] {0, 0, 1}, column));
    }
}
