package mdbms.storage.serializer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;

public final class BinaryRecordSerializer implements IRecordSerializer {
    private final ValueCodec integerCodec = new IntegerCodec();
    private final ValueCodec floatCodec = new FloatCodec();
    private final ValueCodec charCodec = new CharCodec();
    private final ValueCodec varcharCodec = new VarcharCodec();

    // mengubah isi baris jadi data biner sesuai skema
    @Override
    public byte[] serialize(List<Object> values, TableSchema schema) {
        List<ColumnDefinition> columns = columns(schema);
        if (values == null || values.size() != columns.size()) {
            throw new IllegalArgumentException("Value count differs from schema");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition column = columns.get(i);
            Object value = values.get(i);
            if (value == null) {
                if (!column.nullable()) {
                    throw new IllegalArgumentException("NULL in non-nullable column " + column.name());
                }
                out.write(0);
            } else {
                out.write(1);
                out.writeBytes(codec(column.type()).encode(value, column));
            }
        }
        return out.toByteArray();
    }

    // membaca data biner jadi isi baris
    @Override
    public List<Object> deserialize(byte[] data, TableSchema schema) {
        List<ColumnDefinition> columns = columns(schema);
        if (data == null) {
            throw new IllegalArgumentException("Record bytes are null");
        }
        List<Object> values = new ArrayList<>(columns.size());
        int position = 0;
        for (ColumnDefinition column : columns) {
            if (position >= data.length) {
                throw new IllegalArgumentException("Truncated null marker");
            }
            int marker = Byte.toUnsignedInt(data[position++]);
            if (marker == 0) {
                if (!column.nullable()) {
                    throw new IllegalArgumentException("NULL in non-nullable column " + column.name());
                }
                values.add(null);
                continue;
            }
            if (marker != 1) {
                throw new IllegalArgumentException("Invalid null marker");
            }
            int size = switch (column.type()) {
                case INTEGER, FLOAT -> 4;
                case CHAR -> column.length();
                case VARCHAR -> {
                    if (data.length - position < 2) {
                        throw new IllegalArgumentException("Truncated VARCHAR length");
                    }
                    int length = (Byte.toUnsignedInt(data[position]) << 8)
                            | Byte.toUnsignedInt(data[position + 1]);
                    yield 2 + length;
                }
            };
            if (size > data.length - position) {
                throw new IllegalArgumentException("Truncated field " + column.name());
            }
            byte[] field = java.util.Arrays.copyOfRange(data, position, position + size);
            values.add(codec(column.type()).decode(field, column));
            position += size;
        }
        if (position != data.length) {
            throw new IllegalArgumentException("Trailing record bytes");
        }
        return values;
    }

    // mengecek skema lalu mengambil daftar kolomnya
    private List<ColumnDefinition> columns(TableSchema schema) {
        if (schema == null || schema.tableName() == null || schema.tableName().isBlank()
                || schema.columns() == null || schema.columns().isEmpty()) {
            throw new IllegalArgumentException("Missing schema columns");
        }
        List<ColumnDefinition> columns = new ArrayList<>(schema.columns());
        Set<String> names = new HashSet<>();
        for (ColumnDefinition column : columns) {
            if (column == null || column.type() == null) {
                throw new IllegalArgumentException("Invalid column");
            }
            CodecSupport.requireColumn(column, column.type());
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("Duplicate column " + column.name());
            }
        }
        return columns;
    }

    private ValueCodec codec(DataType type) {
        return switch (type) {
            case INTEGER -> integerCodec;
            case FLOAT -> floatCodec;
            case CHAR -> charCodec;
            case VARCHAR -> varcharCodec;
        };
    }
}
