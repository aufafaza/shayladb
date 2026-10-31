package mdbms.processor.operator;

import java.util.List;

import mdbms.models.ColumnDefinition;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class ProjectOperator extends QueryUnaryOperator{
    private final List<String> columns;
    public ProjectOperator(IQueryOperator child,List<String> cols){
        super(child);
        columns=List.copyOf(cols);
    }

    public ResultTableSchema getSchema(){
        ResultTableSchema src=childOperator.getSchema();
        if(src==null)return null;
        
        ResultTableSchema out=new ResultTableSchema();
        for(String ref:columns){
            String[] p=parts(ref);
            String table=p[0],name=p[1];
            for(int i=0;i<src.getColumns().size();i++){
                ColumnDefinition c=src.getColumns().get(i);
                String origin=src.getOrigin(i);
                if(c.name().equalsIgnoreCase(name)&&(table==null||table.equalsIgnoreCase(origin))){
                    out.addColumn(origin,c);
                    break;
                }
            }
        }
        
        return out;
    }

    public IResultRow next(){
        IResultRow in=childOperator.next();
        if(in==null)return null;
        
        ResultTableSchema out=new ResultTableSchema();
        ResultRow row=new ResultRow(out);
        for(String ref:columns){String[] p=parts(ref);
            String table=p[0],name=p[1];
            Object v=table==null?in.get(name):in.get(table,name);
            String origin=table==null?in.getSchema().getOrigin(name):table;ColumnDefinition def=null;
            for(int i=0;i<in.getSchema().getColumns().size();i++){
                ColumnDefinition c=in.getSchema().getColumns().get(i);
                if(c.name().equalsIgnoreCase(name)&&(table==null||table.equalsIgnoreCase(in.getSchema().getOrigin(i)))){
                    def=c;break;
                }
            }
            
            if(def==null)def=new ColumnDefinition(name,mdbms.models.DataType.VARCHAR,0,true);
            
            out.addColumn(origin,def);
            row.set(origin==null?"":origin,name,v);
        }
        
        return row;
    }

    private static String[] parts(String ref){
        String name=ref.trim();
        int dot=name.indexOf('.');
        
        return dot<0?new String[]{null,name}:new String[]{name.substring(0,dot),name.substring(dot+1)};
    }
}
