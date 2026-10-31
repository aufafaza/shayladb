package mdbms.optimizer;

import java.util.List;
import mdbms.models.ColumnReference;
import mdbms.models.ExpressionTree;
import mdbms.models.OrderByItem;
import mdbms.models.QueryTree;

public class SelectParser {
    private final ExpressionParser expressionParser;

    public SelectParser(ExpressionParser expressionParser) {
        this.expressionParser = expressionParser;
    }

    public QueryTree parseSelect(TokenStream stream) {
        // TODO
        return null;
    }

    private List<ColumnReference> parseSelectList(TokenStream stream) {
        // TODO
        return List.of();
    }

    private QueryTree parseFromClause(TokenStream stream) {
        // TODO
        return null;
    }

    private QueryTree parseJoin(TokenStream stream) {
        // TODO
        return null;
    }

    private ExpressionTree parseWhereClause(TokenStream stream) {
        // TODO
        return null;
    }

    private List<OrderByItem> parseOrderBy(TokenStream stream) {
        // TODO
        return List.of();
    }
}
