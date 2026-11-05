package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.TableSchema;
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
        }
        return count;
    }
}
