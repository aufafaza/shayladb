package mdbms.optimizer;

import mdbms.models.QueryTree;

public class StatementParser {
    private final ExpressionParser expressionParser;

    public StatementParser(ExpressionParser expressionParser) {
        this.expressionParser = expressionParser;
    }

    public QueryTree parseStatement(TokenStream stream) {
        // TODO
        return null;
    }

    private QueryTree parseUpdate(TokenStream stream) {
        // TODO
        return null;
    }

    private QueryTree parseInsert(TokenStream stream) {
        // TODO
        return null;
    }

    private QueryTree parseDelete(TokenStream stream) {
        // TODO
        return null;
    }

    private QueryTree parseCreateIndex(TokenStream stream) {
        // TODO
        return null;
    }

    private QueryTree parseTransaction(TokenStream stream) {
        // TODO
        return null;
    }
}
