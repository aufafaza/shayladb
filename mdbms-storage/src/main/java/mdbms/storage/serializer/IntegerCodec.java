package mdbms.storage.serializer;

import java.nio.ByteBuffer;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

public final class IntegerCodec implements ValueCodec {
    // mengubah int jadi 4 byte
    @Override
    public byte[] encode(Object value, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.INTEGER);
        if (!(value instanceof Integer integer)) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Expected Integer");
        }
        return ByteBuffer.allocate(4).putInt(integer).array();
    }

    // membaca 4 byte jadi int lagi
    @Override
    public Object decode(byte[] data, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.INTEGER);
        if (data == null || data.length != 4) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "INTEGER requires exactly four bytes");
        }
        return ByteBuffer.wrap(data).getInt();
    }
}
