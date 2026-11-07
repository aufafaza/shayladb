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

public final class SequentialScanOperator implements IQueryOperator{
    private final IDataAccess access; 
    private final ISchemaCatalog catalog; 
    private final IConcurrencyControl concurrency;
    private final int txId;
    private final String table; 
    private List<Row> rows=List.of();
    private int pos;
    private ResultTableSchema tableSchema;

    public SequentialScanOperator(IDataAccess a,ISchemaCatalog c,IConcurrencyControl cc,int tx,String t){
        access=a;
        catalog=c;
        concurrency=cc;
        txId=tx;
        table=t;
    }

    public void open(){
        TableSchema s=catalog.getTableSchema(table);
        if(s==null)throw new IllegalArgumentException("Tabel tidak ditemukan: "+table);
        tableSchema = new ResultTableSchema(s);
        rows=access.readBlock(new DataRetrieval(table,s.columns().stream().map(ColumnDefinition::name).toList(),List.of())).rows();
        pos=0;
    }

    public IResultRow next(){
        if(pos>=rows.size()) return null;
        RowId id=rows.get(pos++).rowId();
        AccessPermission.read(concurrency,txId,new DataItem(table,id));
        Row r=access.fetchByRowId(id);

        ResultRow row= new ResultRow(tableSchema,r.values());
        row.setRowId(id);
        return row;
    }

    public void close(){
        rows=List.of();
        pos=0;
    }
}
