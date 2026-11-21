package mdbms.storage.crud;

import java.util.Arrays;
import java.util.List;
import mdbms.models.schema.TableSchema;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.storage.block.BufferPool;
import mdbms.storage.block.Block;
import mdbms.storage.serializer.IRecordSerializer;
import mdbms.storage.serializer.SlottedPage;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

public class HeapFile{
    // TODO ganti pakai LSN dari FRM kalau alur WAL di StorageManager udah ada, sementara pakai 0 dulu
    static final long NO_LSN = 0;

    private TableSchema schema;
    private String fileName;
    private BufferPool pool;
    private IRecordSerializer serializer;

    public HeapFile(TableSchema schema, BufferPool pool, IRecordSerializer serializer){
        this.schema = schema;
        this.fileName = schema.tableName() + ".tbl";
        this.pool = pool;
        this.serializer = serializer;
    }

    public RowIterator scan(){
        return new HeapFileRowIterator(schema, fileName, pool, serializer, this);
    }

    public Row fetch(RowId rowId){
        Block block = pool.pin(fileName, rowId.blockNumber());

        try{
            SlottedPage page = SlottedPage.of(block.data);
            byte[] record = page.readRecord(rowId.slot());

            if (record == null){
                throw new StorageException(
                    StorageErrorCode.ROW_NOT_FOUND,
                    "Row not found: " + rowId
                );
            }

            List<Object> values = serializer.deserialize(record, schema);
            return new Row(rowId, values);

        } finally{
            pool.unpin(block, false, NO_LSN);
        }
    }

    public RowId reserve(List<Object> values){
        return reserve(values, NO_LSN);
    }

    public RowId reserve(List<Object> values, long pageLsn){
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;

        Block block = pool.pin(fileName, findBlockWithSpace(bytes, pageLsn));

        boolean dirty = false;
        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = page.reserveSlot(bytes);
            writeBack(block, page);
            dirty = true;

            return new RowId(schema.tableName(), block.blockNumber, slot);

        } finally{
            pool.unpin(block, dirty, pageLsn);
        }
    }

    public void releaseReservation(RowId rowId){
        releaseReservation(rowId, NO_LSN);
    }

    public void releaseReservation(RowId rowId, long pageLsn) {
        Block block = pool.pin(fileName, rowId.blockNumber());

        boolean dirty = false;
        try{
            SlottedPage page = SlottedPage.of(block.data);
            page.releaseReservation(rowId.slot());
            writeBack(block, page);
            dirty = true;

        } finally{
            pool.unpin(block, dirty, pageLsn);
        }
    }

    public RowId insert(List<Object> values){
        return insert(values, NO_LSN);
    }

    public RowId insert(List<Object> values, long pageLsn) {
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;
        Block block = pool.pin(fileName, findBlockWithSpace(bytes, pageLsn));

        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = page.reserveSlot(bytes);
            page.writeSlot(slot, record);
            writeBack(block, page);
            return new RowId(schema.tableName(), block.blockNumber, slot);

        } finally{
            pool.unpin(block, true, pageLsn);
        }
    }

    // taruh baris di RowId tertentu, false kalau slot nya udah berisi baris yang persis sama jadi ga ada yang berubah
    // block atau slot yang belum ada dibikin dulu biar redo setelah crash tetap jalan walau halamannya belum sempat ke disk
    public boolean insertAt(RowId rowId, List<Object> values) {
        return insertAt(rowId, values, NO_LSN);
    }
    
    
    public boolean insertAt(RowId rowId, List<Object> values, long pageLsn){
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;
        while (blockCount() <= rowId.blockNumber()){
            appendEmptyPage(pageLsn);
        }
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = rowId.slot();
            page.ensureSlot(slot);

            if (page.isOccupied(slot)){
                if (Arrays.equals(page.readRecord(slot), record)){
                    if (pageLsn > 0) {
                        dirty = true;
                    }
                    return false;
                }
                throw new StorageException(
                    StorageErrorCode.SLOT_UNAVAILABLE,
                    "Slot unavailable for row: " + rowId
                );
            }

            if (!page.reserveSlotAt(slot, bytes) && !page.isReserved(slot)){
                throw new StorageException(
                    StorageErrorCode.SLOT_UNAVAILABLE,
                    "Page has no room for row: " + rowId
                );
            }

            page.writeSlot(slot, record);
            writeBack(block, page);
            dirty = true;
            return true;

        } finally{
            pool.unpin(block, dirty, pageLsn);
        }
    }

    // ganti isi baris di tempat, kalau ga muat baris baru di insert dulu baru yang lama dihapus biar data ga hilang kalau gagal
    public Row update(RowId rowId, List<Object> values) {
        return update(rowId, values, NO_LSN);
    }
    
    public Row update(RowId rowId, List<Object> values, long pageLsn){
        if (updateInPlace(rowId, values, pageLsn)){
            return new Row(rowId, values);
        }

        RowId moved = insert(values, pageLsn);
        try{
            delete(rowId, pageLsn);
        } catch (RuntimeException e){
            try{
                delete(moved, pageLsn);
            } catch (RuntimeException cleanup){
                e.addSuppressed(cleanup);
            }
            throw e;
        }

        return new Row(moved, values);
    }

    public Row delete(RowId rowId) {
        return delete(rowId, NO_LSN);
    }
    
    // true kalau isi baru muat di slot yang sama, ga ngubah apa apa
    // hasil nya bisa basi kalau ruang halaman keburu dipakai, jadi tetap pakai updateInPlace yang bisa nolak
    public boolean canUpdateInPlace(RowId rowId, List<Object> values) {
        return canUpdateInPlace(rowId, values, NO_LSN);
    }

    public boolean canUpdateInPlace(RowId rowId, List<Object> values, long pageLsn){
        byte[] record = serializer.serialize(values, schema);
        Block block = pool.pin(fileName, rowId.blockNumber());

        try{
            SlottedPage page = SlottedPage.of(block.data);
            return page.canReplace(rowId.slot(), record.length);

        } finally{
            pool.unpin(block, false, pageLsn);
        }
    }

    // ganti isi baris di slot yang sama, false kalau ga muat dan halaman ga diubah sama sekali
    // ga pernah mindahin baris sendiri biar relokasi bisa dikunci dan dicatat dulu sama pemanggil
    public boolean updateInPlace(RowId rowId, List<Object> values) {
        return updateInPlace(rowId, values, NO_LSN);
    }

    public boolean updateInPlace(RowId rowId, List<Object> values, long pageLsn){
        byte[] record = serializer.serialize(values, schema);
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            if (!page.replaceRecord(rowId.slot(), record)){
                return false;
            }
            writeBack(block, page);
            dirty = true;
            return true;

        } finally{
            pool.unpin(block, dirty, pageLsn);
        }
    }

    public Row delete(RowId rowId, long pageLsn){
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            byte[] record = page.readRecord(rowId.slot());

            if (record == null){
                throw new StorageException(
                    StorageErrorCode.ROW_NOT_FOUND,
                    "Row not found: " + rowId
                );
            }

            List<Object> values = serializer.deserialize(record, schema);
            page.deleteRecord(rowId.slot());
            writeBack(block, page);
            dirty = true;

            return new Row(rowId, values);

        } finally{
            pool.unpin(block, dirty, pageLsn);
        }
    }

    // hapus baris kalau masih ada, null kalau block slot atau barisnya udah ga ada jadi aman diulang sama redo dan undo FRM
    public Row deleteIfPresent(RowId rowId) {
        return deleteIfPresent(rowId, NO_LSN);
    }
    
    public Row deleteIfPresent(RowId rowId, long pageLsn){
        if (rowId.blockNumber() >= blockCount()){
            return null;
        }
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = rowId.slot();

            if (slot < 0 || slot >= page.slotCount() || !page.isOccupied(slot)){
                return null;
            }

            List<Object> values = serializer.deserialize(page.readRecord(slot), schema);
            page.deleteRecord(slot);
            writeBack(block, page);
            dirty = true;

            return new Row(rowId, values);

        } finally{
            pool.unpin(block, dirty, pageLsn);
        }
    }

    public int truncate(){
        int rowCount = 0;
    
        try (RowIterator iterator = scan()){
            while (iterator.hasNext()){
                iterator.next();
                rowCount++;
            }
        }
    
        pool.truncateFile(fileName);
    
        return rowCount;
    }

    // lepas semua slot yang masih dipesan dan balikin jumlahnya, cuma aman dipanggil pas ga ada transaksi aktif
    // slot dipesan yang ikut ke disk sebelum crash bakal nyangkut, kalau ga dilepas ruangnya kepake selamanya
    public int releaseAllReservations() {
        return releaseAllReservations(NO_LSN);
    }

    public int releaseAllReservations(long pageLsn){
        int released = 0;
        for (int blockNumber = 0; blockNumber < blockCount(); blockNumber++){
            Block block = pool.pin(fileName, blockNumber);
            boolean dirty = false;
            try{
                SlottedPage page = SlottedPage.of(block.data);
                for (int slot = 0; slot < page.slotCount(); slot++){
                    if (page.isReserved(slot)){
                        page.releaseReservation(slot);
                        released++;
                        dirty = true;
                    }
                }
                if (dirty){
                    writeBack(block, page);
                }
            } finally{
                pool.unpin(block, dirty, pageLsn);
            }
        }
        return released;
    }

    public int blockCount(){
        return pool.blockCount(fileName);
    }

    private int findBlockWithSpace(int requiredBytes, long pageLsn){
        int blockCount = blockCount();
        if (blockCount == 0) return appendEmptyPage(pageLsn);

        int blockNumber = blockCount - 1;
        Block block = pool.pin(fileName, blockNumber);
        boolean hasSpace;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            hasSpace = page.hasSpace(requiredBytes);

        } finally{
            pool.unpin(block, false, pageLsn);
        }

        if (hasSpace) return blockNumber;
        
        return appendEmptyPage(pageLsn);
    }

    // tambah block baru lalu isi header SlottedPage kosong, pin dari appendBlock langsung dilepas
    private int appendEmptyPage(long pageLsn){
        Block block = pool.appendBlock(fileName);
        boolean dirty = false;

        try{
            writeBack(block, new SlottedPage());
            dirty = true;
            return block.blockNumber;

        } finally{
            pool.unpin(block, dirty, pageLsn);
        }
    }

    // SlottedPage cuma salinan, jadi hasil ubahannya harus disalin balik ke data block
    private static void writeBack(Block block, SlottedPage page){
        byte[] bytes = page.toBytes();
        System.arraycopy(bytes, 0, block.data, 0, bytes.length);
    }
}