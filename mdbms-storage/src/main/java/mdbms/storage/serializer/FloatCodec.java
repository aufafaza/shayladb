package mdbms.storage.serializer;

import java.nio.ByteBuffer;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;

public final class FloatCodec implements ValueCodec {
    // mengubah float jadi 4 byte
    @Override
    public byte[] encode(Object value, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.FLOAT);
        if (!(value instanceof Float number)) {
            throw new IllegalArgumentException("Expected Float");
        }
        return ByteBuffer.allocate(4).putInt(Float.floatToRawIntBits(number)).array();
    }

    // membaca 4 byte jadi float lagi
    @Override
    public Object decode(byte[] data, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.FLOAT);
        if (data == null || data.length != 4) {
            throw new IllegalArgumentException("FLOAT requires exactly four bytes");
        }
        return Float.intBitsToFloat(ByteBuffer.wrap(data).getInt());
    }
}
