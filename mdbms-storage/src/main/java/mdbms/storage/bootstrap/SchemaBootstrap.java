package mdbms.storage.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.ForeignKeyConstraint;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.TableSchema;
import mdbms.storage.StorageManager;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

// pasang schema dari SchemaScript ke data directory sebelum server nyala, DDL di sini administratif bukan transaksional
// seluruh skrip divalidasi dulu sebelum ada yang ditulis, kalau gagal di tengah semua yang barusan dibuat dihapus lagi
public final class SchemaBootstrap {
    private final StorageManager storage;

    public SchemaBootstrap(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    // ringkasan hasil bootstrap, yang skipped itu definisi yang udah ada persis sama jadi aman dijalanin ulang
    public record Result(List<String> createdTables, List<String> skippedTables,
                         List<String> createdIndexes, List<String> skippedIndexes) {}

    // parse lalu terapkan skrip schema
    public Result apply(String source) {
        return apply(SchemaScriptParser.parse(source));
    }

    // validasi semua definisi terhadap skrip dan katalog yang ada, baru bikin tabel lalu index lalu flush ke disk
    public Result apply(SchemaScript script) {
        Objects.requireNonNull(script, "script");
        Map<String, TableSchema> existingTables = existingTables();
        Map<String, IndexInfo> existingIndexes = existingIndexes(existingTables);
        Plan plan = validate(script, existingTables, existingIndexes);

        List<String> createdTables = new ArrayList<>();
        List<String> createdIndexes = new ArrayList<>();
        try {
            for (TableSchema schema : plan.tablesToCreate) {
                storage.updateTableSchema(schema);
                createdTables.add(schema.tableName());
            }
            for (IndexInfo info : plan.indexesToCreate) {
                storage.createIndex(info.indexName(), info.tableName(), info.columnName());
                createdIndexes.add(info.indexName());
            }
            storage.flushBuffer();
        } catch (RuntimeException failure) {
            rollback(createdTables, createdIndexes, failure);
            throw failure;
        }
        return new Result(List.copyOf(createdTables), List.copyOf(plan.skippedTables),
                List.copyOf(createdIndexes), List.copyOf(plan.skippedIndexes));
    }

    // cek nama dobel, tipe, primary key, foreign key, dan index, definisi yang bentrok sama katalog ditolak
    private Plan validate(SchemaScript script, Map<String, TableSchema> existingTables,
                          Map<String, IndexInfo> existingIndexes) {
        Plan plan = new Plan();
        Map<String, TableSchema> visible = new HashMap<>(existingTables);
        Map<String, Integer> scriptTables = new HashMap<>();

        for (SchemaScript.TableDefinition definition : script.tables()) {
            TableSchema schema = definition.schema();
            String name = schema.tableName();
            Integer earlier = scriptTables.putIfAbsent(name, definition.line());
            if (earlier != null) {
                throw error(definition.line(), "Table " + name + " is already defined on line " + earlier);
            }
            TableSchema existing = existingTables.get(name);
            if (existing != null) {
                if (!existing.equals(schema)) {
                    throw error(definition.line(), "Table " + name
                            + " already exists with a different definition, drop the data directory or rename the table");
                }
                plan.skippedTables.add(name);
            } else {
                plan.tablesToCreate.add(schema);
            }
            visible.put(name, schema);
        }

        for (SchemaScript.TableDefinition definition : script.tables()) {
            TableSchema schema = definition.schema();
            for (String key : schema.primaryKey()) {
                if (column(schema, key) == null) {
                    throw error(definition.line(), "PRIMARY KEY column " + key + " does not exist in " + schema.tableName());
                }
            }
            List<String> seen = new ArrayList<>();
            for (ColumnDefinition column : schema.columns()) {
                if (seen.contains(column.name())) {
                    throw error(definition.line(), "Column " + column.name() + " is declared twice in " + schema.tableName());
                }
                seen.add(column.name());
            }
            for (ForeignKeyConstraint fk : schema.foreignKeys()) {
                validateForeignKey(definition.line(), schema, fk, visible);
            }
        }

        Map<String, Integer> scriptIndexes = new HashMap<>();
        for (SchemaScript.IndexDefinition definition : script.indexes()) {
            IndexInfo info = definition.info();
            Integer earlier = scriptIndexes.putIfAbsent(info.indexName(), definition.line());
            if (earlier != null) {
                throw error(definition.line(), "Index " + info.indexName() + " is already defined on line " + earlier);
            }
            TableSchema table = visible.get(info.tableName());
            if (table == null) {
                throw error(definition.line(), "Index " + info.indexName() + " refers to unknown table " + info.tableName());
            }
            if (column(table, info.columnName()) == null) {
                throw error(definition.line(), "Index " + info.indexName() + " refers to unknown column "
                        + info.tableName() + "." + info.columnName());
            }
            IndexInfo existing = existingIndexes.get(info.indexName());
            if (existing != null) {
                if (!existing.equals(info)) {
                    throw error(definition.line(), "Index " + info.indexName() + " already exists with a different definition");
                }
                plan.skippedIndexes.add(info.indexName());
            } else {
                plan.indexesToCreate.add(info);
            }
        }
        return plan;
    }

    // tabel dan kolom rujukan harus ada, tipe nya harus sama biar perbandingan nilai FK ga ngaco
    private static void validateForeignKey(int line, TableSchema schema, ForeignKeyConstraint fk,
                                           Map<String, TableSchema> visible) {
        ColumnDefinition source = column(schema, fk.column());
        if (source == null) {
            throw error(line, "FOREIGN KEY column " + fk.column() + " does not exist in " + schema.tableName());
        }
        TableSchema referenced = visible.get(fk.referencedTable());
        if (referenced == null) {
            throw error(line, "FOREIGN KEY " + schema.tableName() + "." + fk.column()
                    + " refers to unknown table " + fk.referencedTable());
        }
        ColumnDefinition target = column(referenced, fk.referencedColumn());
        if (target == null) {
            throw error(line, "FOREIGN KEY " + schema.tableName() + "." + fk.column()
                    + " refers to unknown column " + fk.referencedTable() + "." + fk.referencedColumn());
        }
        if (source.type() != target.type()) {
            throw error(line, "FOREIGN KEY " + schema.tableName() + "." + fk.column() + " is " + source.type()
                    + " but " + fk.referencedTable() + "." + fk.referencedColumn() + " is " + target.type());
        }
    }

    // hapus index lalu tabel yang dibuat di run ini dengan urutan terbalik, error rollback ditempel ke error asli
    private void rollback(List<String> createdTables, List<String> createdIndexes, RuntimeException failure) {
        for (int i = createdIndexes.size() - 1; i >= 0; i--) {
            try {
                storage.dropIndex(createdIndexes.get(i));
            } catch (RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
        for (int i = createdTables.size() - 1; i >= 0; i--) {
            try {
                storage.dropTable(createdTables.get(i));
            } catch (RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
    }

    private Map<String, TableSchema> existingTables() {
        Map<String, TableSchema> tables = new HashMap<>();
        for (String name : storage.listTable()) {
            tables.put(name, storage.getTableSchema(name));
        }
        return tables;
    }

    private Map<String, IndexInfo> existingIndexes(Map<String, TableSchema> tables) {
        Map<String, IndexInfo> indexes = new HashMap<>();
        for (String table : tables.keySet()) {
            for (IndexInfo info : storage.getIndexes(table)) {
                indexes.put(info.indexName(), info);
            }
        }
        return indexes;
    }

    private static ColumnDefinition column(TableSchema schema, String name) {
        for (ColumnDefinition column : schema.columns()) {
            if (column.name().equals(name)) return column;
        }
        return null;
    }

    private static StorageException error(int line, String message) {
        return new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Schema script line " + line + ": " + message);
    }

    // daftar yang mau dibuat dan yang dilewati karena udah ada persis sama
    private static final class Plan {
        private final List<TableSchema> tablesToCreate = new ArrayList<>();
        private final List<String> skippedTables = new ArrayList<>();
        private final List<IndexInfo> indexesToCreate = new ArrayList<>();
        private final List<String> skippedIndexes = new ArrayList<>();
    }
}
