package mdbms.optimizer.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.Token;
import mdbms.optimizer.lexer.TokenStream;
import mdbms.optimizer.lexer.TokenType;

public class StatementParser {
    private final ExpressionParser expressionParser;
    private final String sqlSource;
    private final QueryErrorCollector errorCollector;

    public StatementParser(ExpressionParser expressionParser) {
        this(expressionParser, null, null);
    }

    //Use the same SQL/collector context for the lexer, stream and expression parser
    public StatementParser(ExpressionParser expressionParser, String sqlSource, QueryErrorCollector errorCollector) {
        this.expressionParser = Objects.requireNonNull(expressionParser);
        this.sqlSource = sqlSource;
        this.errorCollector = errorCollector;
    }

    //Parses one non-SELECT statement; the facade owns the following semicolon/EOF
    public QueryTree parseStatement(TokenStream stream) {
        QueryTree tree = switch (stream.peek().getType()) {
            case updateTok -> parseUpdate(stream);
            case insertTok -> parseInsert(stream);
            case deleteTok -> parseDelete(stream);
            case createTok -> parseCreateIndex(stream);
            case beginTok, commitTok -> parseTransaction(stream);
            case idTok -> {
                if (!isKeyword(stream, "ABORT")) {
                    throw syntaxError(stream.peek(), "Unsupported statement '" + stream.peek().getLexeme() + "'");
                }
                yield parseTransaction(stream);
            }
            default -> throw syntaxError(stream.peek(), "Expected UPDATE, INSERT, DELETE, CREATE INDEX, "
                + "BEGIN, COMMIT, or ABORT");
        };
        if (stream.peek().getType() != TokenType.semicolon && !stream.isAtEnd()) {
            throw syntaxError(stream.peek(), "Expected ';' or end of statement; unexpected or misplaced clause");
        }
        return tree;
    }

    private QueryTree parseUpdate(TokenStream stream) {
        stream.expect(TokenType.updateTok);
        String table = stream.expect(TokenType.idTok).getLexeme();
        stream.expect(TokenType.setTok);
        ColumnReference column = parseColumnReference(stream);
        stream.expect(TokenType.eql);
        ExpressionTree value = expressionParser.parseExpression(stream);
        QueryTree update = node(QueryNodeType.UPDATE, table, parseOptionalWhere(stream));
        update.setUpdateAssignment(column, value);
        return update;
    }

    private QueryTree parseInsert(TokenStream stream) {
        stream.expect(TokenType.insertTok);
        stream.expect(TokenType.intoTok);
        String table = stream.expect(TokenType.idTok).getLexeme();
        stream.expect(TokenType.valuesTok);
        stream.expect(TokenType.lparent);
        List<ExpressionTree> values = new ArrayList<>();
        do {
            Token start = stream.peek();
            ExpressionTree value = expressionParser.parseExpression(stream);
            ExpressionNode.Type type = value.getRoot().getType();
            if (type != ExpressionNode.Type.INTEGER_LITERAL && type != ExpressionNode.Type.REAL_LITERAL
                && type != ExpressionNode.Type.STRING_LITERAL) {
                throw syntaxError(start, "INSERT VALUES requires integer, real, or string literals");
            }
            values.add(value);
        } while (stream.match(TokenType.comma));
        stream.expect(TokenType.rparent);
        QueryTree insert = node(QueryNodeType.INSERT, table, List.of());
        insert.setInsertValues(values);
        return insert;
    }

    private QueryTree parseDelete(TokenStream stream) {
        stream.expect(TokenType.deleteTok);
        stream.expect(TokenType.fromTok);
        String table = stream.expect(TokenType.idTok).getLexeme();
        return node(QueryNodeType.DELETE, table, parseOptionalWhere(stream));
    }

    private QueryTree parseCreateIndex(TokenStream stream) {
        stream.expect(TokenType.createTok);
        stream.expect(TokenType.indexTok);
        String name = stream.expect(TokenType.idTok).getLexeme();
        stream.expect(TokenType.onTok);
        String table = stream.expect(TokenType.idTok).getLexeme();
        stream.expect(TokenType.lparent);
        ColumnReference column = new ColumnReference(null, stream.expect(TokenType.idTok).getLexeme());
        stream.expect(TokenType.rparent);
        QueryTree index = node(QueryNodeType.CREATE_INDEX, table, List.of());
        index.setIndexDefinition(name, column);
        return index;
    }

    private QueryTree parseTransaction(TokenStream stream) {
        if (stream.match(TokenType.beginTok)) {
            stream.match(TokenType.transactionTok);
            return node(QueryNodeType.BEGIN, null, List.of());
        }
        if (stream.match(TokenType.commitTok)) {
            return node(QueryNodeType.COMMIT, null, List.of());
        }
        // ABORT is still an identifier in the shared lexer.
        if (isKeyword(stream, "ABORT")) {
            stream.advance();
            return node(QueryNodeType.ABORT, null, List.of());
        }
        throw syntaxError(stream.peek(), "Expected BEGIN, COMMIT, or ABORT");
    }

    private List<ExpressionTree> parseOptionalWhere(TokenStream stream) {
        return stream.match(TokenType.whereTok) ? List.of(expressionParser.parsePredicate(stream)) : List.of();
    }

    private ColumnReference parseColumnReference(TokenStream stream) {
        String first = stream.expect(TokenType.idTok).getLexeme();
        if (stream.match(TokenType.period)) {
            return new ColumnReference(first, stream.expect(TokenType.idTok).getLexeme());
        }
        return new ColumnReference(null, first);
    }

    private QueryTree node(QueryNodeType type, String table, List<ExpressionTree> conditions) {
        return new QueryTree(type, PhysicalAlgorithm.DEFAULT, table, conditions, List.of(), 0);
    }

    private boolean isKeyword(TokenStream stream, String keyword) {
        return stream.peek().getType() == TokenType.idTok
            && keyword.equalsIgnoreCase(stream.peek().getLexeme());
    }

    private QuerySyntaxException syntaxError(Token token, String message) {
        QuerySyntaxException exception = new QuerySyntaxException(message, token, sqlSource);
        if (errorCollector != null) {
            errorCollector.addException(exception);
        }
        return exception;
    }
}
