package mdbms.optimizer;

import mdbms.models.QueryTree;

public class ShaylaParser implements IQueryParser {
    private final ShaylaLexer lexer;

    public ShaylaParser(ShaylaLexer lexer) {
        this.lexer = lexer;
    }

    @Override
    public QueryTree parseToTree(String sqlTree) {
        // TODO
        return null;
    }
}
