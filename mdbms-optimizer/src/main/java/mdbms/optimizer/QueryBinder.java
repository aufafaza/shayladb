package mdbms.optimizer;

import mdbms.api.ISchemaCatalog;
import mdbms.models.ColumnReference;
import mdbms.models.ExpressionTree;
import mdbms.models.QueryTree;
import mdbms.models.TableSchema;

public class QueryBinder {
    private final ISchemaCatalog schemaCatalog;

    public QueryBinder(ISchemaCatalog schemaCatalog) {
        this.schemaCatalog = schemaCatalog;
    }

    public BindingContext bind(QueryTree query) {
        // TODO
        return null;
    }

    private void bindNode(QueryTree node, BindingContext context) {
        // TODO
    }

    private TableSchema resolveTable(String tableName) {
        // TODO
        return null;
    }

    private ColumnBinding resolveColumn(ColumnReference reference, BindingContext context) {
        // TODO
        return null;
    }

    private void bindExpression(ExpressionTree expression, BindingContext context) {
        // TODO
    }

    private void expandSelectStar(QueryTree query, BindingContext context) {
        // TODO
    }

    private void resolveNaturalJoin(QueryTree query, BindingContext context) {
        // TODO
    }
}
