package mdbms.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.OrderByItem;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;

public class SelectParser {
    private final ExpressionParser expressionParser;
    private final String sqlSource;
    private final QueryErrorCollector errorCollector;

    public SelectParser(ExpressionParser expressionParser) {
        this(expressionParser, null, null);
    }

    //Pass the same SQL/collector to the lexer, stream, and expression parser
    public SelectParser(ExpressionParser expressionParser, String sqlSource, QueryErrorCollector errorCollector) {
        this.expressionParser = Objects.requireNonNull(expressionParser);
        this.sqlSource = sqlSource;
        this.errorCollector = errorCollector;
    }

    //Reads SELECT and FROM, the facade owns the following semicolon/EOF
    public QueryTree parseSelect(TokenStream stream) {
        stream.expect(TokenType.selectTok);
        List<ColumnReference> columns = parseSelectList(stream);
        stream.expect(TokenType.fromTok);
        QueryTree source = parseFromClause(stream);
        if (stream.peek().getType() != TokenType.semicolon && !stream.isAtEnd()) {
            throw syntaxError(stream, "Expected ';' or end of SELECT; unsupported or misplaced clause");
        }
        return new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(source), 0, columns, false);
    }

    private List<ColumnReference> parseSelectList(TokenStream stream) {
        if (stream.match(TokenType.star)) {
            return List.of(new ColumnReference(null, "*"));
        }
        List<ColumnReference> columns = new ArrayList<>();
        do {
            Token first = expectIdentifier(stream);
            if (stream.match(TokenType.period)) {
                columns.add(new ColumnReference(first.getLexeme(), expectIdentifier(stream).getLexeme()));
            } else {
                columns.add(new ColumnReference(null, first.getLexeme()));
            }
        } while (stream.match(TokenType.comma));
        return List.copyOf(columns);
    }

    private QueryTree parseFromClause(TokenStream stream) {
        return parseJoin(stream);
    }

    //Reads a FROM source and folds successive Cartesian/conditional/natural joins to the left
    private QueryTree parseJoin(TokenStream stream) {
        QueryTree left = parseTable(stream);
        while (true) {
            if (stream.match(TokenType.comma)) {
                left = new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null,
                    List.of(), List.of(left, parseTable(stream)), 0);
            } else if (isKeyword(stream, "JOIN") || isKeyword(stream, "NATURAL")) {
                boolean natural = isKeyword(stream, "NATURAL");
                if (natural) {
                    stream.advance();
                }
                expectKeyword(stream, "JOIN");
                QueryTree right = parseTable(stream);
                List<ExpressionTree> conditions = List.of();
                if (!natural) {
                    stream.expect(TokenType.onTok);
                    conditions = List.of(expressionParser.parsePredicate(stream));
                }
                left = new QueryTree(QueryNodeType.JOIN, PhysicalAlgorithm.DEFAULT, null,
                    conditions, List.of(left, right), 0, List.of(), natural);
            } else {
                return left;
            }
        }
    }

    private QueryTree parseTable(TokenStream stream) {
        String tableName = expectIdentifier(stream).getLexeme();
        return new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, tableName,
            List.of(), List.of(), 0);
    }

    private Token expectIdentifier(TokenStream stream) {
        if (isKeyword(stream, "JOIN") || isKeyword(stream, "NATURAL") || isOrderBy(stream)) {
            throw syntaxError(stream, "Expected an identifier before '" + stream.peek().getLexeme() + "'");
        }
        return stream.expect(TokenType.idTok);
    }

    private boolean isOrderBy(TokenStream stream) {
        int next = stream.getPosition() + 1;
        return isKeyword(stream, "ORDER") && next < stream.getTokens().size()
            && stream.getTokens().get(next).getType() == TokenType.idTok
            && "BY".equalsIgnoreCase(stream.getTokens().get(next).getLexeme());
    }

    // JOIN/NATURAL are contextual keywords until the shared lexer defines dedicated tokens.
    private boolean isKeyword(TokenStream stream, String keyword) {
        return stream.peek().getType() == TokenType.idTok
            && keyword.equalsIgnoreCase(stream.peek().getLexeme());
    }

    private void expectKeyword(TokenStream stream, String keyword) {
        if (!isKeyword(stream, keyword)) {
            throw syntaxError(stream, "Expected " + keyword + " but found '" + stream.peek().getLexeme() + "'");
        }
        stream.advance();
    }

    private QuerySyntaxException syntaxError(TokenStream stream, String message) {
        QuerySyntaxException exception = new QuerySyntaxException(message, stream.peek(), sqlSource);
        if (errorCollector != null) {
            errorCollector.addException(exception);
        }
        return exception;
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
