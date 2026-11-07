package mdbms.processor.operator;

import java.util.List;

import mdbms.models.data.Condition;
import mdbms.processor.ExpressionEvaluator;
import mdbms.processor.IResultRow;

public class SelectOperator extends QueryUnaryOperator {
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
