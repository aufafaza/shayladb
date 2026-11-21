package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.IResultRow;

public class DeleteOperator extends QueryUnaryOperator implements IWriteOperator {
    private final IDataAccess access;
    private final IConcurrencyControl concurrency;
    private final int txId;
    private final String table;
    private List<IResultRow> targets;
    private int affectedRows;

    public DeleteOperator(IQueryOperator child,IDataAccess a,IConcurrencyControl cc,int tx,String t){
        super(child);
        access=a;
        concurrency=cc;
        txId=tx;
        table=t;
    }

    @Override public void open(){
        targets=new ArrayList<>();
        childOperator.open();
        IResultRow r;
        while((r=childOperator.next())!=null)targets.add(r);
        childOperator.close();
    }

    @Override public IResultRow next(){
        if(targets==null||targets.isEmpty())return null;
        List<IResultRow> rows=targets;
        targets=List.of();
        AccessPermission.write(concurrency,txId,DataItem.tableRange(table),null,null);
        for(IResultRow r:rows){
            DataItem item=new DataItem(table,r.getRowId());
            // lock first, then refresh before image from storage
            AccessPermission.write(concurrency,txId,item,null,null);
            Row latest=access.fetchByRowId(r.getRowId());
            List<Object> oldValues;
            if(latest!=null&&latest.values()!=null){
                oldValues=new ArrayList<>(latest.values());
            }else{
                oldValues=new ArrayList<>();
                for(ColumnDefinition c:r.getSchema().getColumns())oldValues.add(r.get(c.name()));
            }
            AccessPermission.write(concurrency,txId,item,oldValues,null);
            access.deleteByRowId(r.getRowId());
        }
        affectedRows=rows.size();
        return null;
    }

    @Override public void close(){
    }

    public int getAffectedRows(){
        return affectedRows;
    }
}
