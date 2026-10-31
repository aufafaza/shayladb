package mdbms.processor.operator;

import java.util.List;

import mdbms.models.Condition;
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.IResultRow;

public class SelectOperator extends QueryUnaryOperator {
    private final List<Condition> conditions;
    public SelectOperator(IQueryOperator child,List<Condition> c){
        super(child);
        conditions=List.copyOf(c);
    }
    
    public IResultRow next(){
        IResultRow r;
        while((r=childOperator.next())!=null){
            if(ExpressionEvaluator.matches(conditions,r)){
                return r;
            }
        }
        return null;
    }
}
