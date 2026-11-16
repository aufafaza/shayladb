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
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;

        Block block = pool.pin(fileName, findBlockWithSpace(bytes));

        boolean dirty = false;
        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = page.reserveSlot(bytes);
            writeBack(block, page);
            dirty = true;

            return new RowId(schema.tableName(), block.blockNumber, slot);

        } finally{
            pool.unpin(block, dirty, NO_LSN);
        }
    }

    public void releaseReservation(RowId rowId){
        Block block = pool.pin(fileName, rowId.blockNumber());

        boolean dirty = false;
        try{
            SlottedPage page = SlottedPage.of(block.data);
            page.releaseReservation(rowId.slot());
            writeBack(block, page);
            dirty = true;

        } finally{
            pool.unpin(block, dirty, NO_LSN);
        }
    }

    public RowId insert(List<Object> values){
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;
        Block block = pool.pin(fileName, findBlockWithSpace(bytes));

        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = page.reserveSlot(bytes);
            page.writeSlot(slot, record);
            writeBack(block, page);
            return new RowId(schema.tableName(), block.blockNumber, slot);

        } finally{
            pool.unpin(block, true, NO_LSN);
        }
    }

    // taruh baris di RowId tertentu, false kalau slot nya udah berisi baris yang persis sama jadi ga ada yang berubah
    // block atau slot yang belum ada dibikin dulu biar redo setelah crash tetap jalan walau halamannya belum sempat ke disk
    public boolean insertAt(RowId rowId, List<Object> values){
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;
        while (blockCount() <= rowId.blockNumber()){
            appendEmptyPage();
        }
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = rowId.slot();
            page.ensureSlot(slot);

            if (page.isOccupied(slot)){
                if (Arrays.equals(page.readRecord(slot), record)){
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
            pool.unpin(block, dirty, NO_LSN);
        }
    }

    // ganti isi baris di tempat, kalau ga muat baris baru di insert dulu baru yang lama dihapus biar data ga hilang kalau gagal
    public Row update(RowId rowId, List<Object> values){
        byte[] record = serializer.serialize(values, schema);
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            int slot = rowId.slot();
            boolean replaced = page.replaceRecord(slot, record);

            if (replaced) {
                writeBack(block, page);
                dirty = true;
                return new Row(rowId, values);
            }

        } finally{
            pool.unpin(block, dirty, NO_LSN);
        }

        RowId moved = insert(values);
        try{
            delete(rowId);
        } catch (RuntimeException e){
            try{
                delete(moved);
            } catch (RuntimeException cleanup){
                e.addSuppressed(cleanup);
            }
            throw e;
        }

        return new Row(moved, values);
    }

    public Row delete(RowId rowId){
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
            pool.unpin(block, dirty, NO_LSN);
        }
    }

    // hapus baris kalau masih ada, null kalau block slot atau barisnya udah ga ada jadi aman diulang sama redo dan undo FRM
    public Row deleteIfPresent(RowId rowId){
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
            pool.unpin(block, dirty, NO_LSN);
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

    public int blockCount(){
        return pool.blockCount(fileName);
    }

    private int findBlockWithSpace(int requiredBytes){
        int blockCount = blockCount();
        if (blockCount == 0) return appendEmptyPage();

        int blockNumber = blockCount - 1;
        Block block = pool.pin(fileName, blockNumber);
        boolean hasSpace;

        try{
            SlottedPage page = SlottedPage.of(block.data);
            hasSpace = page.hasSpace(requiredBytes);

        } finally{
            pool.unpin(block, false, NO_LSN);
        }

        if (hasSpace) return blockNumber;
        
        return appendEmptyPage();
    }

    // tambah block baru lalu isi header SlottedPage kosong, pin dari appendBlock langsung dilepas
    private int appendEmptyPage(){
        Block block = pool.appendBlock(fileName);
        boolean dirty = false;

        try{
            writeBack(block, new SlottedPage());
            dirty = true;
            return block.blockNumber;

        } finally{
            pool.unpin(block, dirty, NO_LSN);
        }
    }

    // SlottedPage cuma salinan, jadi hasil ubahannya harus disalin balik ke data block
    private static void writeBack(Block block, SlottedPage page){
        byte[] bytes = page.toBytes();
        System.arraycopy(bytes, 0, block.data, 0, bytes.length);
    }
}