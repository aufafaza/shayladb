package mdbms.storage.crud;

import java.util.List;
import java.util.NoSuchElementException;
import mdbms.models.schema.TableSchema;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.storage.block.BufferPool;
import mdbms.storage.block.Block;
import mdbms.storage.serializer.IRecordSerializer;
import mdbms.storage.serializer.SlottedPage;

public class HeapFileRowIterator implements RowIterator {
    private TableSchema schema;
    private String fileName;
    private BufferPool pool;
    private IRecordSerializer serializer;
    private HeapFile heapFile;
    private int currentBlock;
    private int currentSlot;
    private Row nextRow;
    private boolean closed;

    public HeapFileRowIterator(TableSchema schema, String fileName, BufferPool pool, IRecordSerializer serializer, HeapFile heapFile){
        this.schema = schema;
        this.fileName = fileName;
        this.pool = pool;
        this.serializer = serializer;
        this.heapFile = heapFile;
        this.currentBlock = 0;
        this.currentSlot = 0;
        this.nextRow = null;
        this.closed = false;
    }

    @Override
    public boolean hasNext(){
        if (closed) return false;
        if (nextRow != null) return true;

        while(currentBlock < heapFile.blockCount()){
            Block block = pool.pin(fileName, currentBlock);

            try{
                SlottedPage page = SlottedPage.of(block.data);

                while (currentSlot < page.slotCount()){
                
                    if (!page.isOccupied(currentSlot)) {
                        currentSlot++;
                        continue;
                    }
                    
                    byte[] record = page.readRecord(currentSlot);
                    RowId rowId = new RowId(schema.tableName(), currentBlock, currentSlot);
                    List<Object> values = serializer.deserialize(record, schema);
                    nextRow = new Row(rowId, values);

                    currentSlot++;
                    
                    return true;
                }

            } finally{
                pool.unpin(block, false, HeapFile.NO_LSN);
            }
            
            currentBlock++;
            currentSlot = 0;
        }

        return false;
    }

    @Override
    public Row next(){
        if (!hasNext()) throw new NoSuchElementException();

        Row row = nextRow;
        nextRow = null;
        
        return row;
    }

    @Override
    public void close(){
        closed = true;
        nextRow = null;
    }
}