package mdbms.optimizer.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.OrderByClause;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.query.SortDirection;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.Token;
import mdbms.optimizer.lexer.TokenStream;
import mdbms.optimizer.lexer.TokenType;

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

    //Reads one SELECT; the facade owns the following semicolon/EOF
    public QueryTree parseSelect(TokenStream stream) {
        stream.expect(TokenType.selectTok);
        List<ColumnReference> columns = parseSelectList(stream);
        stream.expect(TokenType.fromTok);
        QueryTree source = parseFromClause(stream);
        if (stream.peek().getType() == TokenType.whereTok) {
            ExpressionTree predicate = parseWhereClause(stream);
            source = new QueryTree(QueryNodeType.SELECT, PhysicalAlgorithm.DEFAULT, null,
                List.of(predicate), List.of(source), 0);
        }
        if (isKeyword(stream, "ORDER")) {
            List<OrderByClause> orderBy = parseOrderBy(stream);
            // Keep sort columns available even when they are outside the SELECT projection
            QueryTree sort = new QueryTree(QueryNodeType.SORT, PhysicalAlgorithm.DEFAULT, null,
                List.of(), List.of(source), 0);
            sort.setOrderByClauses(orderBy);
            source = sort;
        }
        if (isKeyword(stream, "LIMIT")) {
            int limitValue = parseLimit(stream);
            QueryTree limit = new QueryTree(QueryNodeType.LIMIT, PhysicalAlgorithm.DEFAULT, null,
                List.of(), List.of(source), 0);
            limit.setLimit(limitValue);
            source = limit;
        }
        if (stream.peek().getType() != TokenType.semicolon && !stream.isAtEnd()) {
            throw syntaxError(stream, "Expected ';' or end of SELECT; unexpected or misplaced clause");
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
            columns.add(parseColumnReference(stream));
        } while (stream.match(TokenType.comma));
        return List.copyOf(columns);
    }

    private ColumnReference parseColumnReference(TokenStream stream) {
        Token first = expectIdentifier(stream);
        if (stream.match(TokenType.period)) {
            return new ColumnReference(first.getLexeme(), expectIdentifier(stream).getLexeme());
        }
        return new ColumnReference(null, first.getLexeme());
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
        if (isKeyword(stream, "JOIN") || isKeyword(stream, "NATURAL")
            || isOrderBy(stream) || isKeyword(stream, "LIMIT")) {
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
        stream.expect(TokenType.whereTok);
        return expressionParser.parsePredicate(stream);
    }

    private List<OrderByClause> parseOrderBy(TokenStream stream) {
        expectKeyword(stream, "ORDER");
        expectKeyword(stream, "BY");
        List<OrderByClause> clauses = new ArrayList<>();
        do {
            if (isKeyword(stream, "ASC") || isKeyword(stream, "DESC")) {
                throw syntaxError(stream, "Expected an ORDER BY column before its direction");
            }
            ColumnReference field = parseColumnReference(stream);
            SortDirection direction = SortDirection.ASC;
            if (isKeyword(stream, "ASC") || isKeyword(stream, "DESC")) {
                direction = isKeyword(stream, "DESC") ? SortDirection.DESC : SortDirection.ASC;
                stream.advance();
            }
            clauses.add(new OrderByClause(field, direction));
        } while (stream.match(TokenType.comma));
        return List.copyOf(clauses);
    }

    private int parseLimit(TokenStream stream) {
        expectKeyword(stream, "LIMIT");
        if (stream.peek().getType() != TokenType.intLitTok) {
            throw syntaxError(stream, "Expected non-negative integer literal after LIMIT");
        }
        Token limitToken = stream.advance();
        try {
            int value = Integer.parseInt(limitToken.getLexeme());
            if (value < 0) {
                throw syntaxError(stream, "LIMIT must not be negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw syntaxError(stream, "Invalid integer literal for LIMIT: '" + limitToken.getLexeme() + "'");
        }
    }
}
