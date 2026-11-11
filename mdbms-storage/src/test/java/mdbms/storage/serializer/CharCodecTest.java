package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;

class CharCodecTest {
    private final CharCodec codec = new CharCodec();
    private final ColumnDefinition column = new ColumnDefinition("name", DataType.CHAR, 5, false);

    // memastikan char utf-8 dibaca dan diberi padding dengan benar
    @Test
    void padsAndReadsUtf8ByByteLength() {
        byte[] encoded = codec.encode("é", column);
        assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0xa9, 0, 0, 0}, encoded);
        assertEquals("é", codec.decode(encoded, column));
        assertEquals("", codec.decode(codec.encode("", column), column));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION,
                assertThrows(StorageException.class, () -> codec.encode("ééé", column)).getCode());
    }

    // memastikan nul dan teks rusak ditolak
    @Test
    void rejectsNulAndMalformedText() {
        assertThrows(StorageException.class, () -> codec.encode("a\0", column));
        assertThrows(StorageException.class, () -> codec.encode("\ud800", column));
        assertEquals(StorageErrorCode.INVALID_BLOCK,
                assertThrows(StorageException.class,
                        () -> codec.decode(new byte[] {'a', 0, 'b', 0, 0}, column)).getCode());
        assertThrows(StorageException.class,
                () -> codec.decode(new byte[] {(byte) 0xc3, 0, 0, 0, 0}, column));
    }
}
