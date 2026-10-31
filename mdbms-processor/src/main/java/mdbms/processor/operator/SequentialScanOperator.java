package mdbms.processor.operator;

import java.util.List;

import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataRetrieval;
import mdbms.models.Row;
import mdbms.models.TableSchema;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public final class SequentialScanOperator implements IQueryOperator{
    private final IDataAccess access; 
    private final ISchemaCatalog catalog; 
    private final String table; 
    private List<Row> rows=List.of();
    private int pos;

    public SequentialScanOperator(IDataAccess a,ISchemaCatalog c,String t){
        access=a;
        catalog=c;
        table=t;
    }

    public void open(){
        TableSchema s=catalog.getTableSchema(table);
        if(s==null)throw new IllegalArgumentException("Tabel tidak ditemukan: "+table);
        rows=access.readBlock(new DataRetrieval(table,s.columns().stream().map(ColumnDefinition::name).toList(),List.of())).rows();
        pos=0;
    }

    public IResultRow next(){
        if(pos>=rows.size()) return null;
        Row r=rows.get(pos++);

        ResultRow row=ResultRow.from(table,catalog.getTableSchema(table).columns(),r.values());
        row.setRowId(r.rowId());
        return row;
    }

    public ResultTableSchema getSchema(){
        TableSchema ts=catalog.getTableSchema(table);
        ResultTableSchema out=new ResultTableSchema(table);
        
        if(ts!=null)for(ColumnDefinition c:ts.columns())out.addColumn(table,c);
        
        return out;
    }

    public void close(){
        rows=List.of();
        pos=0;
    }
}
