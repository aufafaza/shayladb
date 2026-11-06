package mdbms.storage.crud;

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
            SlottedPage page = new SlottedPage(block.data);
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

    public RowId insert(List<Object> values){
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;
        Block block = pool.pin(fileName, findBlockWithSpace(bytes));

        try{
            SlottedPage page = new SlottedPage(block.data);
            int slot = page.reserveSlot(bytes);
            page.writeSlot(slot, record);
            writeBack(block, page);
            return new RowId(schema.tableName(), block.blockNumber, slot);

        } finally{
            pool.unpin(block, true, NO_LSN);
        }
    }

    public void insertAt(RowId rowId, List<Object> values){
        byte[] record = serializer.serialize(values, schema);
        int bytes = record.length;
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = new SlottedPage(block.data);
            int slot = rowId.slot();

            if (!page.reserveSlotAt(slot, bytes)){
                throw new StorageException(
                    StorageErrorCode.SLOT_UNAVAILABLE,
                    "Slot unavailable for row: " + rowId
                );
            }

            page.writeSlot(slot, record);
            writeBack(block, page);
            dirty = true;

        } finally{
            pool.unpin(block, dirty, NO_LSN);
        }
    }

    public Row update(RowId rowId, List<Object> values){
        byte[] record = serializer.serialize(values, schema);
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = new SlottedPage(block.data);
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

        delete(rowId);        
        return new Row(insert(values), values);
    }

    public Row delete(RowId rowId){
        Block block = pool.pin(fileName, rowId.blockNumber());
        boolean dirty = false;

        try{
            SlottedPage page = new SlottedPage(block.data);
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

    // TODO nyalain lagi kalau BufferPool dan BlockManager SM2 udah punya truncate(fileName)
    // public int truncate(){
    //     int rowCount = 0;
    //
    //     try (RowIterator iterator = scan()){
    //         while (iterator.hasNext()){
    //             iterator.next();
    //             rowCount++;
    //         }
    //     }
    //
    //     pool.truncate(fileName);
    //
    //     return rowCount;
    // }

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
            SlottedPage page = new SlottedPage(block.data);
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