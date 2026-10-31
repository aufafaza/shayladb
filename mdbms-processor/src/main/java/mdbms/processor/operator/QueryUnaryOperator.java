package mdbms.processor.operator;

public abstract class QueryUnaryOperator implements IQueryOperator {
    private final IQueryOperator childOperator;
    
    protected QueryUnaryOperator(IQueryOperator childOperator) {
        this.childOperator = childOperator;
    }
}
