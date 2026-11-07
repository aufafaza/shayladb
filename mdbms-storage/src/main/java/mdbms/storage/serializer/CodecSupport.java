package mdbms.storage.serializer;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

final class CodecSupport {
    private CodecSupport() {}

    // validasi kolom sesuai tipenya
    static void requireColumn(ColumnDefinition column, DataType type) {
        if (column == null || column.name() == null || column.name().isBlank()
                || column.type() != type) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Invalid column for " + type);
        }
        if (column.length() < 0 || ((type == DataType.CHAR || type == DataType.VARCHAR)
                && (column.length() == 0 || column.length() > 65535))) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Invalid length for " + column.name());
        }
    }

    // mengubah teks jadi byte utf-8
    static byte[] encodeUtf8(Object value) {
        if (!(value instanceof String string) || string.indexOf('\0') >= 0) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Expected a string without NUL");
        }
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(string));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        } catch (CharacterCodingException ex) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Invalid UTF-16 input", ex);
        }
    }

    // membaca potongan byte utf-8 jadi teks
    static String decodeUtf8(byte[] data, int offset, int length) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length)).toString();
            if (value.indexOf('\0') >= 0) {
                throw new StorageException(StorageErrorCode.INVALID_BLOCK, "NUL is not allowed in strings");
            }
            return value;
        } catch (CharacterCodingException ex) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Invalid UTF-8 data", ex);
        }
    }
}
