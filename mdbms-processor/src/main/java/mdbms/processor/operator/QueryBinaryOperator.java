package mdbms.processor.operator;

public abstract class QueryBinaryOperator implements IQueryOperator {
    private final IQueryOperator leftOperator;
    private final IQueryOperator rightOperator;
    
    protected QueryBinaryOperator(IQueryOperator leftOperator, IQueryOperator rightOperator) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
    }
}