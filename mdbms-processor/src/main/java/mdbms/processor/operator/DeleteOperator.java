package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.models.data.DataItem;
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
        for(IResultRow r:targets){
            List<Object> oldValues=new ArrayList<>();
            for(ColumnDefinition c:r.getSchema().getColumns())oldValues.add(r.get(c.name()));
            AccessPermission.write(concurrency,txId,new DataItem(table,r.getRowId()),oldValues,null);
            access.deleteByRowId(r.getRowId());
        }
        affectedRows=targets.size();
        return null;
    }

    @Override public void close(){
    }

    public int getAffectedRows(){
        return affectedRows;
    }
}
