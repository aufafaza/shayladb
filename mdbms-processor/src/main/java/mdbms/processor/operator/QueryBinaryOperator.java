package mdbms.processor.operator;

public abstract class QueryBinaryOperator implements IQueryOperator {
    protected final IQueryOperator leftOperator;
    protected final IQueryOperator rightOperator;
    
    protected QueryBinaryOperator(IQueryOperator leftOperator, IQueryOperator rightOperator) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
    }
}