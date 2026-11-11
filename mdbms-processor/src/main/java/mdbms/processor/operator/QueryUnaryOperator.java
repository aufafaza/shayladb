package mdbms.processor.operator;

import mdbms.processor.IResultRow;

public abstract class QueryUnaryOperator implements IQueryOperator {
    protected final IQueryOperator childOperator;
    
    protected QueryUnaryOperator(IQueryOperator childOperator) {
        this.childOperator = childOperator;
    }
    
    public void open(){
        childOperator.open();
    }

    public void close(){
        childOperator.close();
    }

    public IResultRow next(){
        return childOperator.next();
    }
}
