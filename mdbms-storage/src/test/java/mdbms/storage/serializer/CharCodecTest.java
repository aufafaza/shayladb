package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
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
        assertThrows(IllegalArgumentException.class, () -> codec.encode("ééé", column));
    }

    // memastikan nul dan teks rusak ditolak
    @Test
    void rejectsNulAndMalformedText() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode("a\0", column));
        assertThrows(IllegalArgumentException.class, () -> codec.encode("\ud800", column));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new byte[] {'a', 0, 'b', 0, 0}, column));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new byte[] {(byte) 0xc3, 0, 0, 0, 0}, column));
    }
}
