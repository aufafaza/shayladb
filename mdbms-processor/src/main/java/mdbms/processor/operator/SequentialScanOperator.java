package mdbms.processor.operator;

import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.TableSchema;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

/**
 * Phantom policy: a protected scan holds SHARED on the table range (blocks concurrent INSERTs until it ends)
 * plus SHARED on every row it returns. The unprotected variant is only used to pick UPDATE/DELETE targets;
 * those operators lock each target row exclusively and verify it is unchanged before writing.
 */
public final class SequentialScanOperator implements IQueryOperator, IRowProducingOperator {
    private final IDataAccess access; 
    private final ISchemaCatalog catalog; 
    private final IConcurrencyControl concurrency;
    private final int txId;
    private final String table; 
    private final boolean protectRange;
    private List<Row> rows=List.of();
    private int pos;
    private ResultTableSchema tableSchema;

    public SequentialScanOperator(IDataAccess access, ISchemaCatalog catalog,
            IConcurrencyControl concurrency, int txId, String table) {
        this(access, catalog, concurrency, txId, table, true);
    }

    public SequentialScanOperator(IDataAccess access, ISchemaCatalog catalog,
            IConcurrencyControl concurrency, int txId, String table, boolean protectRange) {
        this.access=access; 
        this.catalog=catalog; 
        this.concurrency=concurrency;
        this.txId=txId; 
        this.table=table;
        this.protectRange=protectRange;
    }

    @Override
    public ResultTableSchema getSchema() {
        if (tableSchema == null) {
            TableSchema schema = catalog.getTableSchema(table);
            if (schema == null) throw new IllegalArgumentException("Tabel tidak ditemukan: "+table);
            tableSchema = new ResultTableSchema(schema);
        }
        return tableSchema;
    }


    @Override public void open() {
        TableSchema schema=catalog.getTableSchema(table);
        if(schema==null) throw new IllegalArgumentException("Tabel tidak ditemukan: "+table);
        tableSchema=new ResultTableSchema(schema);
        if (protectRange) AccessPermission.read(concurrency,txId,DataItem.tableRange(table));
        rows=access.readBlock(new DataRetrieval(table,
                schema.columns().stream().map(ColumnDefinition::name).toList(), List.of())).rows();
        pos=0;
    }

    @Override public IResultRow next() {
        while(pos<rows.size()) {
            Row scanned=rows.get(pos++);
            RowId id=scanned.rowId();
            if (protectRange) AccessPermission.read(concurrency,txId,new DataItem(table,id));
            Row current=access.fetchByRowId(id);
            if(current==null || current.values()==null || current.values().isEmpty()) continue;
            ResultRow result=new ResultRow(tableSchema,current.values());
            result.setRowId(id);
            return result;
        }
        return null;
    }

    @Override public void close() { 
        rows=List.of(); 
        pos=0; 
    }
}
