package mdbms.optimizer;

import mdbms.models.DataType;
import mdbms.models.ExpressionTree;
import mdbms.models.QueryTree;

public class QueryValidator implements IQueryValidator {
    @Override
    public boolean validate(QueryTree tree) {
        // TODO
        return false;
    }

    public void validate(QueryTree query, BindingContext context) {
        // TODO
    }

    private void validateNode(QueryTree node, BindingContext context) {
        // TODO
    }

    private void validateColumnTypes(ExpressionTree expression, BindingContext context) {
        // TODO
    }

    private DataType validateExpressionTypes(ExpressionTree expression, BindingContext context) {
        // TODO
        return null;
    }

    private void validateInsertValues(QueryTree node, BindingContext context) {
        // TODO
    }

    private void validateUpdateAssignments(QueryTree node, BindingContext context) {
        // TODO
    }
}
