package mdbms.storage.serializer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;

class BinaryRecordSerializerTest {
    private final BinaryRecordSerializer serializer = new BinaryRecordSerializer();
    private final TableSchema schema = new TableSchema("people", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 0, false),
            new ColumnDefinition("score", DataType.FLOAT, 0, true),
            new ColumnDefinition("code", DataType.CHAR, 4, false),
            new ColumnDefinition("name", DataType.VARCHAR, 20, true)), List.of(), List.of());

    // memastikan null nilai kosong dan nol tetap bisa dibedakan
    @Test
    void roundTripDistinguishesNullEmptyAndZero() {
        List<Object> values = new ArrayList<>(Arrays.asList(0, null, "é", ""));
        byte[] bytes = serializer.serialize(values, schema);
        assertEquals(values, serializer.deserialize(bytes, schema));
        assertEquals(List.of(1, 0, 1), List.of((int) bytes[0], (int) bytes[5], (int) bytes[6]));
        values.set(1, 2.5f);
        values.set(3, null);
        assertEquals(values, serializer.deserialize(serializer.serialize(values, schema), schema));
    }

    // memastikan bentuk data salah dan record rusak ditolak
    @Test
    void rejectsWrongShapeAndCorruptRecord() {
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION,
                assertThrows(StorageException.class,
                        () -> serializer.serialize(List.of(1), schema)).getCode());
        List<Object> bad = new ArrayList<>(Arrays.asList(null, null, "x", "a"));
        assertThrows(StorageException.class, () -> serializer.serialize(bad, schema));
        byte[] valid = serializer.serialize(Arrays.asList(1, null, "x", "a"), schema);
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        assertEquals(StorageErrorCode.INVALID_BLOCK,
                assertThrows(StorageException.class,
                        () -> serializer.deserialize(truncated, schema)).getCode());
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(StorageException.class, () -> serializer.deserialize(trailing, schema));
        valid[0] = 2;
        assertThrows(StorageException.class, () -> serializer.deserialize(valid, schema));
    }
}
