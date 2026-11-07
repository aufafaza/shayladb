package mdbms.storage.serializer;

import java.util.List;
import mdbms.models.schema.TableSchema;

public interface IRecordSerializer {
    // mengubah isi baris jadi data biner
    byte[] serialize(List<Object> values, TableSchema schema);

    // membaca data biner jadi isi baris
    List<Object> deserialize(byte[] data, TableSchema schema);
}
