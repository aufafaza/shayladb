package mdbms.storage.serializer;

import java.nio.ByteBuffer;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

public final class VarcharCodec implements ValueCodec {
    // mengubah teks varchar jadi byte beserta panjangnya
    @Override
    public byte[] encode(Object value, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.VARCHAR);
        byte[] text = CodecSupport.encodeUtf8(value);
        if (text.length > column.length()) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "VARCHAR exceeds byte length");
        }
        return ByteBuffer.allocate(2 + text.length).putShort((short) text.length).put(text).array();
    }

    // membaca byte varchar jadi teks lagi
    @Override
    public Object decode(byte[] data, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.VARCHAR);
        if (data == null || data.length < 2) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Truncated VARCHAR");
        }
        int length = Short.toUnsignedInt(ByteBuffer.wrap(data).getShort());
        if (length > column.length() || data.length != length + 2) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Invalid VARCHAR byte length");
        }
        return CodecSupport.decodeUtf8(data, 2, length);
    }
}
