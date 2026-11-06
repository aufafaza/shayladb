package mdbms.storage.index;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.BufferPool;
import mdbms.storage.catalog.CatalogManager;
import mdbms.storage.crud.RowIterator;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import mdbms.storage.serializer.CharCodec;
import mdbms.storage.serializer.FloatCodec;
import mdbms.storage.serializer.IntegerCodec;
import mdbms.storage.serializer.ValueCodec;
import mdbms.storage.serializer.VarcharCodec;

// pegang semua index yang kebuka, nyatet metadata nya ke katalog SM4, dan nyamain isi index tiap ada perubahan baris
public final class IndexManager {
    private static final int NO_OVERRIDE = 0;
    private static final int MIN_DEGREE = 3;

    private final CatalogManager catalog;
    private final BufferPool pool;
    private final int maxDegreeOverride;
    private final Map<String, Index> indexes = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // maxDegreeOverride 0 artinya pakai degree maksimal dari ukuran key, nilai lain dipakai buat bikin pohon kecil pas testing
    public IndexManager(CatalogManager catalog, BufferPool pool, int maxDegreeOverride) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.pool = Objects.requireNonNull(pool, "pool");
        if (maxDegreeOverride != NO_OVERRIDE && maxDegreeOverride < MIN_DEGREE) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                    "Index maxDegree override must be 0 or at least " + MIN_DEGREE);
        }
        this.maxDegreeOverride = maxDegreeOverride;
    }

    // buka lagi semua index yang tercatat di katalog, dipanggil sekali pas DBMS nyala
    public void loadAll() {
        lock.writeLock().lock();
        try {
            indexes.clear();
            for (IndexInfo info : catalog.getAllIndexes()) {
                TableSchema schema = catalog.getSchema(info.tableName());
                indexes.put(info.indexName(), open(info, columnOf(schema, info.columnName())));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // bikin index baru lalu isi dari baris yang udah ada, baru dicatat ke katalog kalau pengisian berhasil
    // kalau gagal di tengah file index dibuang lagi biar ga ada sisa
    public IndexInfo createIndex(String name, TableSchema schema, String column, RowIterator existingRows) {
        lock.writeLock().lock();
        try {
            ColumnDefinition definition = validateNew(name, schema, column);
            IndexInfo info = new IndexInfo(name, schema.tableName(), column, IndexType.BPLUS_TREE);
            Index index = open(info, definition);
            index.create();
            try {
                while (existingRows.hasNext()) {
                    Row row = existingRows.next();
                    index.insert(keyOf(row, schema, column), row.rowId());
                }
                catalog.registerIndex(info);
            } catch (RuntimeException e) {
                index.destroy();
                throw e;
            }
            indexes.put(name, index);
            return info;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // hapus index dari katalog dulu baru file nya, biar katalog ga pernah nunjuk ke file yang udah hilang
    public void dropIndex(String name) {
        lock.writeLock().lock();
        try {
            Index index = getIndexOrThrow(name);
            catalog.unregisterIndex(name);
            index.destroy();
            indexes.remove(name);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // hapus semua index milik satu tabel, dipanggil sebelum tabelnya di drop
    public void dropIndexesOf(String table) {
        lock.writeLock().lock();
        try {
            for (Index index : indexesOf(table)) {
                dropIndex(index.getInfo().indexName());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // cari RowId dengan key sama persis lewat index bernama ini
    public List<RowId> search(String indexName, Object key) {
        lock.readLock().lock();
        try {
            return getIndexOrThrow(indexName).search(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    // cari RowId dalam rentang key lewat index bernama ini
    public List<RowId> searchRange(String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive) {
        lock.readLock().lock();
        try {
            return getIndexOrThrow(indexName).searchRange(low, high, lowInclusive, highInclusive);
        } finally {
            lock.readLock().unlock();
        }
    }

    // baris baru masuk ke semua index tabelnya
    public void onInsert(Row row, TableSchema schema) {
        lock.readLock().lock();
        try {
            for (Index index : indexesOf(schema.tableName())) {
                index.insert(keyOf(row, schema, index.getInfo().columnName()), row.rowId());
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // baris yang berubah dihapus dari index lalu dimasukin lagi, index yang key dan RowId nya sama dilewatin
    public void onUpdate(Row oldRow, Row newRow, TableSchema schema) {
        lock.readLock().lock();
        try {
            for (Index index : indexesOf(schema.tableName())) {
                String column = index.getInfo().columnName();
                Object oldKey = keyOf(oldRow, schema, column);
                Object newKey = keyOf(newRow, schema, column);
                if (Objects.equals(oldKey, newKey) && oldRow.rowId().equals(newRow.rowId())) continue;
                index.delete(oldKey, oldRow.rowId());
                index.insert(newKey, newRow.rowId());
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // baris yang dihapus dikeluarin dari semua index tabelnya
    public void onDelete(Row row, TableSchema schema) {
        lock.readLock().lock();
        try {
            for (Index index : indexesOf(schema.tableName())) {
                index.delete(keyOf(row, schema, index.getInfo().columnName()), row.rowId());
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // kosongin semua index satu tabel, dipakai pas tabelnya di truncate
    public void clear(String table) {
        lock.readLock().lock();
        try {
            for (Index index : indexesOf(table)) {
                index.clear();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // cek nama index belum dipakai dan kolomnya ada di tabel, balikin definisi kolomnya
    private ColumnDefinition validateNew(String name, TableSchema schema, String column) {
        Objects.requireNonNull(schema, "schema");
        if (name == null || name.isBlank()) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Index name must not be blank");
        }
        boolean registered = catalog.getAllIndexes().stream().anyMatch(i -> i.indexName().equals(name));
        if (indexes.containsKey(name) || registered) {
            throw new StorageException(StorageErrorCode.INDEX_ALREADY_EXISTS, "Index already exists: " + name);
        }
        return columnOf(schema, column);
    }

    // ambil index yang kebuka, INDEX_NOT_FOUND kalau namanya ga dikenal
    private Index getIndexOrThrow(String name) {
        Index index = indexes.get(name);
        if (index == null) {
            throw new StorageException(StorageErrorCode.INDEX_NOT_FOUND, "Index not found: " + name);
        }
        return index;
    }

    // semua index yang nempel di satu tabel
    private List<Index> indexesOf(String table) {
        return indexes.values().stream()
                .filter(index -> index.getInfo().tableName().equals(table))
                .toList();
    }

    // nilai kolom yang diindex dari satu baris lengkap
    private static Object keyOf(Row row, TableSchema schema, String column) {
        List<ColumnDefinition> columns = schema.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(column)) return row.values().get(i);
        }
        throw new StorageException(StorageErrorCode.COLUMN_NOT_FOUND, "Column not found: " + column);
    }

    // definisi kolom dari schema, COLUMN_NOT_FOUND kalau ga ada
    private static ColumnDefinition columnOf(TableSchema schema, String column) {
        for (ColumnDefinition definition : schema.columns()) {
            if (definition.name().equals(column)) return definition;
        }
        throw new StorageException(StorageErrorCode.COLUMN_NOT_FOUND,
                "Column not found: " + column + " in table " + schema.tableName());
    }

    // rakit BPlusTreeIndex dengan codec key yang cocok sama tipe kolom, belum bikin file
    private Index open(IndexInfo info, ColumnDefinition column) {
        ValueCodec codec = switch (column.type()) {
            case INTEGER -> new IntegerCodec();
            case FLOAT -> new FloatCodec();
            case CHAR -> new CharCodec();
            case VARCHAR -> new VarcharCodec();
        };
        int limit = BPlusTreeIndex.computeMaxDegree(column);
        int maxDegree = maxDegreeOverride == NO_OVERRIDE ? limit : Math.min(maxDegreeOverride, limit);
        return new BPlusTreeIndex(info, column, maxDegree, pool, new KeyNormalizer(), new NodeCodec(column, codec));
    }
}
