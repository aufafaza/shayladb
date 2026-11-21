package mdbms.storage.bootstrap;

import java.util.List;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.TableSchema;

// hasil parse file schema, urutan tabel dan index sama kayak di file biar pesan error nunjuk baris yang bener
public record SchemaScript(List<TableDefinition> tables, List<IndexDefinition> indexes) {

    public SchemaScript {
        tables = List.copyOf(tables);
        indexes = List.copyOf(indexes);
    }

    // satu CREATE TABLE beserta nomor baris tempat statement nya mulai
    public record TableDefinition(TableSchema schema, int line) {}

    // satu CREATE INDEX beserta nomor baris tempat statement nya mulai
    public record IndexDefinition(IndexInfo info, int line) {}
}
