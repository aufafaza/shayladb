package mdbms.optimizer;

import java.util.List;
import java.util.Objects;

import mdbms.models.query.QueryTree;

public class ShaylaParser implements IQueryParser {
    private final ShaylaLexer lexer;

    public ShaylaParser(ShaylaLexer lexer) {
        this.lexer = Objects.requireNonNull(lexer, "lexer");
    }

    @Override
    public QueryTree parseToTree(String sql) {
        // sql is source text
        // note that sekarang  returned treenya msh unbound and unoptimized.
        lexer.setInput(sql);
        String source = lexer.getInput();
        QueryErrorCollector collector = lexer.getErrorCollector();
        int previousErrors = collector == null ? 0 : collector.getAllExceptions().size();
        TokenStream stream = lexer.tokenizeToStream();
        rejectLexicalErrors(stream, collector, previousErrors, source);

        ExpressionParser expressions = new ExpressionParser(source, collector);
        QueryTree tree = stream.peek().getType() == TokenType.selectTok
            ? new SelectParser(expressions, source, collector).parseSelect(stream)
            : new StatementParser(expressions, source, collector).parseStatement(stream);

        stream.match(TokenType.semicolon);
        stream.expect(TokenType.EOF);
        return tree;
    }

    private void rejectLexicalErrors(TokenStream stream, QueryErrorCollector collector,
                                     int previousErrors, String source) {
        if (collector != null) {
            List<QueryProcessingException> errors = collector.getAllExceptions();
            // Reuse errors recorded by this tokenization
            for (int i = previousErrors; i < errors.size(); i++) {
                if (errors.get(i).getCategory() == ErrorCategory.LEXICAL_ERROR) {
                    throw errors.get(i);
                }
            }
        }
        // Unknown symbols are tokens in the shared lexer, even without a collector.
        for (Token token : stream.getTokens()) {
            if (token.getType() == TokenType.UNKNOWN) {
                QueryLexicalException exception = new QueryLexicalException(
                    "Unknown symbol: '" + token.getLexeme() + "'", token, source);
                if (collector != null) {
                    collector.addException(exception);
                }
                throw exception;
            }
        }
    }
}
