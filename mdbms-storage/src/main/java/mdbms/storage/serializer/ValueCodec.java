package mdbms.storage.serializer;

import mdbms.models.schema.ColumnDefinition;

public interface ValueCodec {
    byte[] encode(Object value, ColumnDefinition column);

    Object decode(byte[] data, ColumnDefinition column);
}
