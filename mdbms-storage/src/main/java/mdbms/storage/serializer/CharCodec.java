package mdbms.storage.serializer;

import java.util.Arrays;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;

public final class CharCodec implements ValueCodec {
    // ngubah char jadi byte tetap
    @Override
    public byte[] encode(Object value, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.CHAR);
        byte[] text = CodecSupport.encodeUtf8(value);
        if (text.length > column.length()) {
            throw new IllegalArgumentException("CHAR exceeds byte length");
        }
        return Arrays.copyOf(text, column.length());
    }

    // membaca byte char jadi teks lagi
    @Override
    public Object decode(byte[] data, ColumnDefinition column) {
        CodecSupport.requireColumn(column, DataType.CHAR);
        if (data == null || data.length != column.length()) {
            throw new IllegalArgumentException("Invalid CHAR byte length");
        }
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return CodecSupport.decodeUtf8(data, 0, end);
    }
}
