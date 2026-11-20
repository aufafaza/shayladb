package mdbms.processor.operator;

import java.util.List;

import mdbms.models.query.ExpressionTree;
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultTableSchema;

public class SelectOperator extends QueryUnaryOperator implements IRowProducingOperator {
    private final List<ExpressionTree> conditions;

    public SelectOperator(
        IQueryOperator childOperator,
        List<ExpressionTree> conditions
    ) {
        super(childOperator);

        this.conditions = conditions == null
            ? List.of()
            : List.copyOf(conditions);
    }

    @Override
    public ResultTableSchema getSchema() {
        if (childOperator instanceof IRowProducingOperator) {
            return ((IRowProducingOperator) childOperator).getSchema();
        }
        return null;
    }

    @Override
    public IResultRow next() {
        IResultRow row;

        while ((row = childOperator.next()) != null) {
            if (ExpressionEvaluator.matches(conditions, row)) {
                return row;
            }
        }

        return null;
    }
}
