package mdbms.processor.operator;

public abstract class QueryUnaryOperator implements IQueryOperator {
    protected final IQueryOperator childOperator;
    
    protected QueryUnaryOperator(IQueryOperator childOperator) {
        this.childOperator = childOperator;
    }
}
