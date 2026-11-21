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
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.DropBehavior;
import mdbms.models.schema.ForeignKeyAction;
import mdbms.models.schema.ForeignKeyConstraint;
import mdbms.models.schema.TableSchema;
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
            case createTok -> parseCreate(stream);
            case dropTok -> parseDropTable(stream);
            case beginTok, commitTok -> parseTransaction(stream);
            case idTok -> {
                if (!isKeyword(stream, "ABORT")) {
                    throw syntaxError(stream.peek(), "Unsupported statement '" + stream.peek().getLexeme() + "'");
                }
                yield parseTransaction(stream);
            }
            default -> throw syntaxError(stream.peek(), "Expected UPDATE, INSERT, DELETE, CREATE TABLE/INDEX, DROP TABLE, "
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

    private QueryTree parseCreate(TokenStream stream) {
        stream.expect(TokenType.createTok);
        if (stream.match(TokenType.tableTok)) {
            return parseCreateTable(stream);
        }
        if (stream.peek().getType() != TokenType.indexTok) {
            throw syntaxError(stream.peek(), "Unsupported statement 'CREATE " + stream.peek().getLexeme()
                + "'; expected CREATE TABLE or CREATE INDEX");
        }
        return parseCreateIndex(stream);
    }

    private QueryTree parseCreateIndex(TokenStream stream) {
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

    private QueryTree parseCreateTable(TokenStream stream) {
        String table = stream.expect(TokenType.idTok).getLexeme();
        stream.expect(TokenType.lparent);
        List<ColumnDefinition> columns = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        List<ForeignKeyConstraint> foreignKeys = new ArrayList<>();
        do {
            if (stream.match(TokenType.primaryTok)) {
                if (!primaryKey.isEmpty()) throw syntaxError(stream.peek(), "Multiple PRIMARY KEY declarations");
                stream.expect(TokenType.keyTok);
                stream.expect(TokenType.lparent);
                do {
                    primaryKey.add(stream.expect(TokenType.idTok).getLexeme());
                } while (stream.match(TokenType.comma));
                stream.expect(TokenType.rparent);
            } else if (isKeyword(stream, "FOREIGN")) {
                stream.advance();
                stream.expect(TokenType.keyTok);
                stream.expect(TokenType.lparent);
                String column = stream.expect(TokenType.idTok).getLexeme();
                stream.expect(TokenType.rparent);
                foreignKeys.add(parseReferences(stream, column));
            } else {
                String column = stream.expect(TokenType.idTok).getLexeme();
                DataType type;
                int length = 4;
                if (stream.match(TokenType.intTypeTok)) {
                    type = DataType.INTEGER;
                } else if (isKeyword(stream, "FLOAT")) {
                    stream.advance();
                    type = DataType.FLOAT;
                } else if (stream.peek().getType() == TokenType.varcharTok || isKeyword(stream, "CHAR")) {
                    type = stream.advance().getType() == TokenType.varcharTok ? DataType.VARCHAR : DataType.CHAR;
                    stream.expect(TokenType.lparent);
                    Token size = stream.expect(TokenType.intLitTok);
                    try {
                        length = Integer.parseInt(size.getLexeme());
                        if (length <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException error) {
                        throw syntaxError(size, "Text length must be a positive integer within range");
                    }
                    stream.expect(TokenType.rparent);
                } else {
                    throw syntaxError(stream.peek(), "Expected INTEGER, FLOAT, CHAR(n), or VARCHAR(n)");
                }
                boolean nullable = true;
                boolean nullabilitySpecified = false;
                boolean referencesSpecified = false;
                while (true) {
                    if (stream.peek().getType() == TokenType.notTok || stream.peek().getType() == TokenType.nullTok) {
                        if (nullabilitySpecified) throw syntaxError(stream.peek(), "Repeated nullability constraint");
                        nullable = !stream.match(TokenType.notTok);
                        stream.expect(TokenType.nullTok);
                        nullabilitySpecified = true;
                    } else if (stream.match(TokenType.primaryTok)) {
                        if (!primaryKey.isEmpty()) throw syntaxError(stream.peek(), "Multiple PRIMARY KEY declarations");
                        stream.expect(TokenType.keyTok);
                        primaryKey.add(column);
                    } else if (isKeyword(stream, "REFERENCES")) {
                        if (referencesSpecified) throw syntaxError(stream.peek(), "Repeated REFERENCES constraint");
                        foreignKeys.add(parseReferences(stream, column));
                        referencesSpecified = true;
                    } else {
                        break;
                    }
                }
                columns.add(new ColumnDefinition(column, type, length, nullable));
            }
        } while (stream.match(TokenType.comma));
        stream.expect(TokenType.rparent);
        // A PRIMARY KEY is never nullable, including table-level declarations.
        List<ColumnDefinition> normalized = columns.stream().map(column -> new ColumnDefinition(
            column.name(), column.type(), column.length(), column.nullable()
                && primaryKey.stream().noneMatch(key -> key.equalsIgnoreCase(column.name())))).toList();
        QueryTree create = node(QueryNodeType.CREATE_TABLE, table, List.of());
        create.setTableDefinition(new TableSchema(table, normalized, primaryKey, foreignKeys));
        return create;
    }

    private ForeignKeyConstraint parseReferences(TokenStream stream, String column) {
        expectKeyword(stream, "REFERENCES");
        String table = stream.expect(TokenType.idTok).getLexeme();
        stream.expect(TokenType.lparent);
        String target = stream.expect(TokenType.idTok).getLexeme();
        stream.expect(TokenType.rparent);
        ForeignKeyAction onDelete = ForeignKeyAction.NO_ACTION;
        ForeignKeyAction onUpdate = ForeignKeyAction.NO_ACTION;
        boolean deleteSpecified = false;
        boolean updateSpecified = false;
        while (stream.match(TokenType.onTok)) {
            if (stream.match(TokenType.deleteTok)) {
                if (deleteSpecified) throw syntaxError(stream.peek(), "Repeated ON DELETE action");
                onDelete = parseForeignKeyAction(stream);
                deleteSpecified = true;
            } else {
                stream.expect(TokenType.updateTok);
                if (updateSpecified) throw syntaxError(stream.peek(), "Repeated ON UPDATE action");
                onUpdate = parseForeignKeyAction(stream);
                updateSpecified = true;
            }
        }
        return new ForeignKeyConstraint(column, table, target, onDelete, onUpdate);
    }

    private ForeignKeyAction parseForeignKeyAction(TokenStream stream) {
        if (isKeyword(stream, "CASCADE")) { stream.advance(); return ForeignKeyAction.CASCADE; }
        if (isKeyword(stream, "RESTRICT")) { stream.advance(); return ForeignKeyAction.RESTRICT; }
        if (stream.match(TokenType.setTok)) {
            stream.expect(TokenType.nullTok);
            return ForeignKeyAction.SET_NULL;
        }
        expectKeyword(stream, "NO");
        expectKeyword(stream, "ACTION");
        return ForeignKeyAction.NO_ACTION;
    }

    private QueryTree parseDropTable(TokenStream stream) {
        stream.expect(TokenType.dropTok);
        stream.expect(TokenType.tableTok);
        QueryTree drop = node(QueryNodeType.DROP_TABLE, stream.expect(TokenType.idTok).getLexeme(), List.of());
        if (isKeyword(stream, "CASCADE") || isKeyword(stream, "RESTRICT")) {
            drop.setDropBehavior(DropBehavior.valueOf(stream.advance().getLexeme().toUpperCase(java.util.Locale.ROOT)));
        }
        return drop;
    }

    private void expectKeyword(TokenStream stream, String keyword) {
        if (!isKeyword(stream, keyword)) throw syntaxError(stream.peek(), "Expected " + keyword);
        stream.advance();
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
