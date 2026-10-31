package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataRetrieval;
import mdbms.models.Row;
import mdbms.models.Rows;
import mdbms.models.TableSchema;
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.ResultRow;

public class DeleteOperator {
    private final IDataAccess access;
    private final ISchemaCatalog catalog;
    private final String table;
    private final List<Condition> conditions;

    public DeleteOperator(IDataAccess a,ISchemaCatalog c,String t,List<Condition> cond){
        access=a;
        catalog=c;
        table=t;
        conditions=List.copyOf(cond);
    }

    public int execute(){
        TableSchema ts=catalog.getTableSchema(table);
        if(ts==null)throw new IllegalArgumentException("Tabel tidak ditemukan: "+table);
        Rows rs=access.readBlock(new DataRetrieval(table,ts.columns().stream().map(ColumnDefinition::name).toList(),List.of()));
        List<RowId> ids=new ArrayList<>();
        
        for(Row raw:rs.rows())if(ExpressionEvaluator.matches(conditions,ResultRow.from(table,ts.columns(),raw.values())))ids.add(raw.rowId());
        
        int count=0;for(RowId id:ids){
            access.deleteByRowId(id);
            count++;
        }return count;
    }
}
