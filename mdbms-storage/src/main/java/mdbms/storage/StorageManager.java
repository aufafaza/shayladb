package mdbms.storage;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.api.IStatisticsProvider;
import mdbms.models.access.BufferStats;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.data.Condition;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.models.schema.DropBehavior;
import mdbms.models.schema.ColumnDefinition;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import mdbms.storage.crud.ConditionEvaluator;
import mdbms.storage.crud.HeapFile;
import mdbms.storage.crud.RowIterator;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.serializer.BinaryRecordSerializer;
import mdbms.storage.serializer.IRecordSerializer;
import mdbms.storage.catalog.CatalogManager;
import mdbms.storage.statistics.StatisticsManager;
import mdbms.storage.index.IndexManager;

public final class StorageManager implements IDataAccess, ISchemaCatalog, IStatisticsProvider, IDurableStorage{
    static final long NO_LSN = 0;

    private final BufferPool bufferPool;
    private final IRecordSerializer serializer;
    private final CatalogManager catalog;
    private final StatisticsManager statistics;
    private final IndexManager indexManager;
    private final ConditionEvaluator evaluator;
    private final Map<String, HeapFile> tables;

    // rakit semua komponen SM, index yang tercatat di katalog langsung dibuka lagi
    public StorageManager(String dataDirectory, int bufferCapacity, int indexMaxDegree){
        bufferPool = new BufferPool(new BlockManager(dataDirectory), bufferCapacity);
        serializer = new BinaryRecordSerializer();
        catalog = new CatalogManager(dataDirectory);
        statistics = new StatisticsManager(catalog);
        indexManager = new IndexManager(catalog, bufferPool, indexMaxDegree);
        indexManager.loadAll();
        evaluator = new ConditionEvaluator();
        tables = new HashMap<>();
    }
    
    public void attachRecoveryManager(IFailureRecoveryManager frm){
        bufferPool.attachRecoveryManager(frm);
    }

    @Override
    public Rows readBlock(DataRetrieval request){
        TableSchema schema = requireSchema(request.table());

        HeapFile heapFile = heapFileOf(request.table());
        List<Row> result = new ArrayList<>();

        try (RowIterator iterator = heapFile.scan()){
            while (iterator.hasNext()){
                Row row = iterator.next();

                if (request.conditions() != null && !request.conditions().isEmpty() && !matchesAll(request.conditions(), row, schema)){
                    continue;
                }

                result.add(projectRow(row, schema, request.columns()));
            }
        }

        return new Rows(result);
    }

    @Override
    public List<RowId> writeBlock(DataWrite request){
        TableSchema schema = requireSchema(request.table());

        HeapFile heapFile = heapFileOf(request.table());
        List<RowId> result = new ArrayList<>();

        if (request.conditions() == null || request.conditions().isEmpty()){
            List<Object> values = toPositional(schema, request.values());
            validateRow(schema, values);

            RowId rowId = heapFile.insert(values, 0L);
            Row newRow = new Row(rowId, values);
            try{
                afterWrite(request.table(), null, newRow, 0L);
                result.add(rowId);
                return result;
                
            } catch (RuntimeException e){
                undo(e, () -> heapFile.delete(rowId, 0L));
                throw e;
            }
        }

        List<Row> rowsToUpdate = new ArrayList<>();
        try (RowIterator iterator = heapFile.scan()){
            while (iterator.hasNext()){
                Row row = iterator.next();

                if (matchesAll(request.conditions(), row, schema)){
                    rowsToUpdate.add(row);
                }
            }
        }
        for (Row oldRow : rowsToUpdate){
            List<Object> newValues = new ArrayList<>(oldRow.values());

            for (Map.Entry<String, Object> entry : request.values().entrySet()){
                int index = columnIndex(schema, entry.getKey());
                newValues.set(index, entry.getValue());
            }

            validateRow(schema, newValues);
            Row newRow = updateByRowId(oldRow.rowId(), newValues, 0L);
            result.add(newRow.rowId());
        }

        return result;
    }

    @Override
    public int deleteBlock(DataDeletion request){
        TableSchema schema = requireSchema(request.table());

        HeapFile heapFile = heapFileOf(request.table());
        List<RowId> rowsToDelete = new ArrayList<>();

        try (RowIterator iterator = heapFile.scan()){
            while (iterator.hasNext()){
                Row row = iterator.next();

                if (request.conditions() != null
                        && !request.conditions().isEmpty()
                        && !matchesAll(request.conditions(), row, schema)){
                    continue;
                }

                rowsToDelete.add(row.rowId());
            }
        }

        for (RowId rowId : rowsToDelete){
            deleteByRowId(rowId, 0L);
        }

        return rowsToDelete.size();
    }

    @Override
    public Row fetchByRowId(RowId rowId){
        HeapFile heapFile = heapFileOf(rowId.tableName());
        return heapFile.fetch(rowId);
    }

    @Override
    public RowId reserveRowId(String table, List<Object> values) {
        return reserveRowId(table, values, NO_LSN);
    }

    @Override
    public RowId reserveRowId(String table, List<Object> values, long pageLsn){
        TableSchema schema = requireSchema(table);
        validateRow(schema, values);
        HeapFile heapFile = heapFileOf(table);

        return heapFile.reserve(values, pageLsn);
    }

    @Override
    public void releaseRowId(RowId rowId){
        releaseRowId(rowId, NO_LSN);
    }

    @Override
    public void releaseRowId(RowId rowId, long pageLsn){
        HeapFile heapFile = heapFileOf(rowId.tableName());
        heapFile.releaseReservation(rowId, pageLsn);
    }

    // taruh baris di RowId hasil reserveRowId atau dari redo FRM, kalau barisnya udah ada dengan isi sama dianggap beres
    // heap cuma dibalikin kalau baris nya memang baru dimasukin di panggilan ini
    @Override
    public void insertAt(RowId rowId, List<Object> values){
        insertAt(rowId, values, NO_LSN);
    }

    @Override
    public void insertAt(RowId rowId, List<Object> values, long pageLsn){
        TableSchema schema = requireSchema(rowId.tableName());

        validateRow(schema, values);

        HeapFile heapFile = heapFileOf(rowId.tableName());
        boolean inserted = heapFile.insertAt(rowId, values, pageLsn);

        Row newRow = new Row(rowId, new ArrayList<>(values));

        try{
            afterWrite(rowId.tableName(), null, newRow, pageLsn);
        } catch (RuntimeException e){
            if (inserted){
                undo(e, () -> heapFile.delete(rowId, pageLsn));
            }
            throw e;
        }
    }
    @Override
    public Row updateByRowId(RowId rowId, List<Object> values) {
        return updateByRowId(rowId, values, NO_LSN);
    }

    @Override
    public Row updateByRowId(RowId rowId, List<Object> values, long pageLsn){
        TableSchema schema = requireSchema(rowId.tableName());

        validateRow(schema, values);

        HeapFile heapFile = heapFileOf(rowId.tableName());

        Row oldRow = heapFile.fetch(rowId);
        Row newRow = heapFile.update(rowId, values, pageLsn);

        try{
            afterWrite(rowId.tableName(), oldRow, newRow, pageLsn);
            return newRow;
        } catch (RuntimeException e){
            if (newRow.rowId().equals(oldRow.rowId())){
                undo(e, () -> heapFile.update(oldRow.rowId(), oldRow.values(), pageLsn));
            } else{
                undo(e, () -> {
                    heapFile.delete(newRow.rowId(), pageLsn);
                    heapFile.insertAt(oldRow.rowId(), oldRow.values(), pageLsn);
                });
            }

            throw e;
        }
    }

    // cek isi baru muat di slot yang sama atau ga, dipanggil QP sebelum minta lock dan nulis log
    // false berarti UPDATE harus lewat relokasi, jalur ini ga ngubah apa apa
    @Override
    public boolean canUpdateInPlace(RowId rowId, List<Object> values){
        return canUpdateInPlace(rowId, values, NO_LSN);
    }

    @Override
    public boolean canUpdateInPlace(RowId rowId, List<Object> values, long pageLsn){
        TableSchema schema = requireSchema(rowId.tableName());

        validateRow(schema, values);

        return heapFileOf(rowId.tableName()).canUpdateInPlace(rowId, values, pageLsn);
    }

    // UPDATE yang ga pernah mindahin baris, false kalau ga muat dan heap sama index ga berubah
    // bisa false walau canUpdateInPlace tadi true kalau ruang halaman keburu dipakai, pemanggil lanjut lewat relokasi
    @Override
    public boolean updateInPlace(RowId rowId, List<Object> values) {
        return updateInPlace(rowId, values, NO_LSN);
    }

    @Override
    public boolean updateInPlace(RowId rowId, List<Object> values, long pageLsn){
        TableSchema schema = requireSchema(rowId.tableName());

        validateRow(schema, values);

        HeapFile heapFile = heapFileOf(rowId.tableName());

        Row oldRow = heapFile.fetch(rowId);
        if (!heapFile.updateInPlace(rowId, values, pageLsn)){
            return false;
        }

        try{
            afterWrite(rowId.tableName(), oldRow, new Row(rowId, new ArrayList<>(values)), pageLsn);
            return true;
        } catch (RuntimeException e){
            undo(e, () -> heapFile.updateInPlace(oldRow.rowId(), oldRow.values(), pageLsn));
            throw e;
        }
    }

    // hapus baris lewat RowId lalu buang dari semua index, null kalau barisnya udah ga ada
    // dibikin aman diulang biar redo DELETE dan undo INSERT dari FRM ga gagal walau perubahannya udah sampai disk
    @Override
    public Row deleteByRowId(RowId rowId) {
        return deleteByRowId(rowId, NO_LSN);
    }

    @Override
    public Row deleteByRowId(RowId rowId, long pageLsn){
        HeapFile heapFile = heapFileOf(rowId.tableName());

        Row oldRow = heapFile.deleteIfPresent(rowId, pageLsn);
        if (oldRow == null){
            return null;
        }

        try{
            afterWrite(rowId.tableName(), oldRow, null, pageLsn);
            return oldRow;
        } catch (RuntimeException e){
            undo(e, () -> heapFile.insertAt(oldRow.rowId(), oldRow.values(), pageLsn));
            throw e;
        }
    }

    @Override
    public List<RowId> searchIndex(String indexName, Object key){
        return indexManager.search(indexName, key);
    }

    @Override
    public List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive){
        return indexManager.searchRange(indexName, low, high, lowInclusive, highInclusive);
    }

    @Override
    public TableSchema getTableSchema(String table){
        return catalog.tableExists(table) ? catalog.getSchema(table) : null;
    }

    @Override
    public List<String> listTable(){
        return catalog.listTables();
    }

    // daftarin tabel baru ke katalog, nama yang udah ada ditolak biar schema ga ketimpa sementara file datanya masih format lama
    @Override
    public void updateTableSchema(TableSchema schema){
        if (schema != null && catalog.tableExists(schema.tableName())){
            throw new StorageException(StorageErrorCode.TABLE_ALREADY_EXISTS,
                "Table already exists: " + schema.tableName());
        }
        catalog.saveSchema(schema);
    }

    @Override
    public void dropTable(String table){
        dropTable(table, DropBehavior.RESTRICT);
    }

    @Override
    public void dropTable(String table, DropBehavior behavior){
        requireSchema(table);
        catalog.validateDrop(table, behavior);

        indexManager.dropIndexesOf(table);
        bufferPool.discardFile(table + ".tbl");
        tables.remove(table);
        statistics.invalidate(table);
        catalog.dropTable(table, behavior);
    }

    // kosongin semua baris tabel tapi tabelnya tetap ada, index tabel ikut dikosongin dan statistik dihitung ulang
    // balikin jumlah baris yang dihapus, ditolak kalau masih ada block tabel yang lagi di pin
    public int clearTable(String table){
        requireSchema(table);

        int removed = heapFileOf(table).truncate();
        indexManager.clear(table);
        statistics.invalidate(table);
        return removed;
    }

    @Override
    public List<IndexInfo> getIndexes(String table){
        requireSchema(table);
        return catalog.getIndexes(table);
    }

    @Override
    // bikin index B+Tree lalu isi dari semua baris yang udah ada di tabel
    public IndexInfo createIndex(String name, String table, String column){
        TableSchema schema = requireSchema(table);

        try (RowIterator iterator = heapFileOf(table).scan()){
            return indexManager.createIndex(name, schema, column, iterator);
        }
    }

    // hapus satu index dari katalog dan file nya, dipakai bootstrap schema buat batalin index yang baru dibuat
    public void dropIndex(String name){
        indexManager.dropIndex(name);
    }

    @Override
    public Statistic getStatistics(String table){
        return statistics.getStatistics(table, heapFileOf(table));
    }

    @Override
    public void flushBuffer(){
        bufferPool.flushBuffer();
    }

    @Override public void close() {
        bufferPool.flushBuffer();
        bufferPool.discardAllFrames();
        tables.clear();
    }


    @Override
    public BufferStats getBufferStats(){
        return bufferPool.getBufferStats();
    }

    // buang semua cache SM lalu baca ulang katalog dan index dari disk, dipakai FRM setelah file data diganti snapshot
    // perubahan di buffer yang belum di flush sengaja dibuang karena isi disk yang jadi acuan
    public void reloadFromDisk(){
        bufferPool.discardAllFrames();
        reloadCatalogAndIndexes();
    }

    // versi IDurableStorage dari reloadFromDisk, cuma frame file yang diganti yang dibuang biar perubahan file lain ga hilang
    @Override
    public void invalidateAndReload(Set<String> affectedFiles){
        if (affectedFiles == null || affectedFiles.isEmpty()){
            reloadFromDisk();
            return;
        }
        for (String fileName : affectedFiles){
            bufferPool.discardFrames(fileName);
        }
        reloadCatalogAndIndexes();
    }

    // dipanggil FRM setelah recovery selesai, buffer ga dibuang karena hasil redo undo bisa belum ke flush
    @Override
    public void rebuildIndexesAndReloadCatalog(){
        reloadCatalogAndIndexes();
        releaseOrphanReservations();
        rebuildIndexes();
    }

    // lepas slot dipesan yang nyangkut di disk dari transaksi yang ga sempat selesai sebelum crash
    // cuma boleh dipanggil pas ga ada transaksi aktif, balikin jumlah slot yang dilepas
    public int releaseOrphanReservations(){
        int released = 0;
        for (String table : catalog.listTables()){
            released += heapFileOf(table).releaseAllReservations();
        }
        return released;
    }

    // kosongin cache heap dan statistik lalu baca ulang katalog dan buka ulang index sesuai katalog terbaru
    private void reloadCatalogAndIndexes(){
        tables.clear();
        for (String table : catalog.listTables()){
            statistics.invalidate(table);
        }
        catalog.load();
        indexManager.loadAll();
    }

    // bangun ulang semua index dari isi heap, dipanggil setelah recovery karena halaman heap dan index bisa ke flush ga barengan
    public void rebuildIndexes(){
        for (String table : catalog.listTables()){
            if (catalog.getIndexes(table).isEmpty()){
                continue;
            }

            try (RowIterator iterator = heapFileOf(table).scan()){
                indexManager.rebuild(catalog.getSchema(table), iterator);
            }
        }
    }

    // ambil schema tabel, getSchema SM4 udah lempar TABLE_NOT_FOUND sendiri kalau tabelnya ga ada
    private TableSchema requireSchema(String table){
        return catalog.getSchema(table);
    }

    // jalanin pembatalan di heap, kalau pembatalannya ikut gagal errornya ditempel ke error asli biar ga ketutup
    private static void undo(RuntimeException cause, Runnable action){
        try{
            action.run();
        } catch (RuntimeException e){
            cause.addSuppressed(e);
        }
    }

    private HeapFile heapFileOf(String table){
        HeapFile heapFile = tables.get(table);

        if (heapFile != null){
            return heapFile;
        }

        TableSchema schema = requireSchema(table);

        heapFile = new HeapFile(schema, bufferPool, serializer);
        tables.put(table, heapFile);
        return heapFile;
    }

    private Row projectRow(Row row, TableSchema schema, List<String> columns){
        if (columns == null || columns.isEmpty()){
            return row;
        }

        List<Object> projectedValues = new ArrayList<>();
        List<ColumnDefinition> definitions = schema.columns();

        for (String column : columns){
            int index = -1;

            for (int i = 0; i < definitions.size(); i++){
                if (definitions.get(i).name().equals(column)){
                    index = i;
                    break;
                }
            }

            if (index < 0){
                throw new StorageException(
                    StorageErrorCode.COLUMN_NOT_FOUND,
                    "Column not found: " + column
                );
            }

            projectedValues.add(row.values().get(index));
        }

        return new Row(row.rowId(), projectedValues);
    }

    private boolean matchesAll(List<Condition> conditions, Row row, TableSchema schema){
        for (Condition condition : conditions){
            if (!evaluator.evaluate(condition, row, schema)){
                return false;
            }
        }

        return true;
    }

    private int columnIndex(TableSchema schema, String column){
        List<ColumnDefinition> columns = schema.columns();

        for (int i = 0; i < columns.size(); i++){
            if (columns.get(i).name().equals(column)){
                return i;
            }
        }

        throw new StorageException(
            StorageErrorCode.COLUMN_NOT_FOUND,
            "Column not found: " + column
        );
    }

    private List<Object> toPositional(TableSchema schema, Map<String, Object> values){
        for (String column : values.keySet()){
            columnIndex(schema, column);
        }

        List<Object> result = new ArrayList<>();

        for (ColumnDefinition column : schema.columns()){
            result.add(values.get(column.name()));
        }

        return result;
    }

    private void validateRow(TableSchema schema, List<Object> values){
        List<ColumnDefinition> columns = schema.columns();

        if (values.size() != columns.size()){
            throw new StorageException(
                StorageErrorCode.SCHEMA_VIOLATION,
                "Value count does not match schema"
            );
        }

        for (int i = 0; i < columns.size(); i++){
            ColumnDefinition column = columns.get(i);
            Object value = values.get(i);

            if (value == null){
                if (!column.nullable()){
                    throw new StorageException(
                        StorageErrorCode.SCHEMA_VIOLATION,
                        "Column cannot be null: " + column.name()
                    );
                }
                continue;
            }

            switch (column.type()){
                case INTEGER -> {
                    if (!(value instanceof Integer)){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Invalid type for column: " + column.name()
                        );
                    }
                }

                case FLOAT -> {
                    if (!(value instanceof Float)){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Invalid type for column: " + column.name()
                        );
                    }
                }

                case CHAR, VARCHAR -> {
                    if (!(value instanceof String)){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Invalid type for column: " + column.name()
                        );
                    }

                    String stringValue = (String) value;
                    int byteLength = stringValue.getBytes(StandardCharsets.UTF_8).length;

                    if (column.length() > 0 && byteLength > column.length()){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Value too long for column: " + column.name()
                        );
                    }
                }
            }
        }
    }

    // samain semua index tabel dengan perubahan baris lalu catat perubahannya ke statistik secara eager
    private void afterWrite(String table, Row oldRow, Row newRow, long pageLsn){
        TableSchema schema = requireSchema(table);

        if (oldRow == null){
            indexManager.onInsert(newRow, schema, pageLsn);
            statistics.recordWrite(table, 1);
        } else if (newRow == null){
            indexManager.onDelete(oldRow, schema, pageLsn);
            statistics.recordWrite(table, -1);
        } else{
            indexManager.onUpdate(oldRow, newRow, schema, pageLsn);
            statistics.recordWrite(table, 0);
        }
    }
}
