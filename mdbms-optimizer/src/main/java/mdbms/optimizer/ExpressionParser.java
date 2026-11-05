package mdbms.optimizer;

import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;

public class ExpressionParser {
    private final String sqlSource;
    private final QueryErrorCollector errorCollector;

    public ExpressionParser() {
        this(null, null);
    }

    /** Optional query context for diagnostics created by this parser. */
    public ExpressionParser(String sqlSource, QueryErrorCollector errorCollector) {
        this.sqlSource = sqlSource;
        this.errorCollector = errorCollector;
    }

    /** Reads an arithmetic value expression; the caller owns following clause/delimiter tokens. */
    public ExpressionTree parseExpression(TokenStream stream) {
        Token start = stream.peek();
        ExpressionNode root = parseAdditive(stream);
        requireValue(root, start);
        expectBoundary(stream, true);
        return new ExpressionTree(root);
    }

    /** Reads comparisons joined by AND, leaving the next clause/delimiter unconsumed. */
    public ExpressionTree parsePredicate(TokenStream stream) {
        Token start = stream.peek();
        ExpressionNode root = parseLogicalAnd(stream);
        expectBoundary(stream, false);
        requirePredicate(root, start);
        return new ExpressionTree(root);
    }

    private ExpressionNode parseLogicalAnd(TokenStream stream) {
        Token start = stream.peek();
        ExpressionNode left = parseComparison(stream);
        while (stream.peek().getType() == TokenType.andTok) {
            requirePredicate(left, start);
            stream.advance();
            Token rightStart = stream.peek();
            ExpressionNode right = parseComparison(stream);
            requirePredicate(right, rightStart);
            left = ExpressionNode.logicalAnd(left, right);
        }
        return left;
    }

    private ExpressionNode parseComparison(TokenStream stream) {
        ExpressionNode left = parseAdditive(stream);
        if (!isComparison(stream.peek().getType())) {
            return left;
        }

        Token operator = stream.peek();
        requireValue(left, operator);
        stream.advance();
        Token rightStart = stream.peek();
        ExpressionNode right = parseAdditive(stream);
        requireValue(right, rightStart);
        if (isComparison(stream.peek().getType())) {
            throw syntaxError(stream, "Chained comparisons are not supported; combine comparisons with AND");
        }
        String symbol = operator.getType() == TokenType.neq ? "<>" : operator.getLexeme();
        return ExpressionNode.comparison(symbol, left, right);
    }

    private ExpressionNode parseAdditive(TokenStream stream) {
        // Each operand includes its higher-priority multiplication/division first.
        ExpressionNode left = parseMultiplicative(stream);
        while (stream.peek().getType() == TokenType.plus || stream.peek().getType() == TokenType.minus) {
            requireValue(left, stream.peek());
            Token operator = stream.advance();
            Token rightStart = stream.peek();
            ExpressionNode right = parseMultiplicative(stream);
            requireValue(right, rightStart);
            // reuse the previous tree on the left (ex. 5 - 3 - 2 mean (5 - 3) - 2.)
            left = ExpressionNode.binary(operator.getLexeme(), left, right);
        }
        return left;
    }

    private ExpressionNode parseMultiplicative(TokenStream stream) {
        ExpressionNode left = parseUnary(stream);
        while (stream.peek().getType() == TokenType.star || stream.peek().getType() == TokenType.rdiv) {
            requireValue(left, stream.peek());
            Token operator = stream.advance();
            Token rightStart = stream.peek();
            ExpressionNode right = parseUnary(stream);
            requireValue(right, rightStart);
            left = ExpressionNode.binary(operator.getLexeme(), left, right);
        }
        return left;
    }

    private ExpressionNode parseUnary(TokenStream stream) {
        // Unary signs are reserved for a later grammar extension.
        return parsePrimary(stream);
    }

    /** Reads a literal, column reference, or parenthesized arithmetic expression. */
    ExpressionNode parsePrimary(TokenStream stream) {
        Token token = stream.peek();
        if (isContextualClause(stream)) {
            throw syntaxError(stream, "Expected an operand before clause '" + token.getLexeme() + "'");
        }
        switch (token.getType()) {
            case intLitTok:
                stream.advance();
                return ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, token.getLexeme());
            case realLitTok:
                stream.advance();
                return ExpressionNode.literal(ExpressionNode.Type.REAL_LITERAL, token.getLexeme());
            case strTok:
                stream.advance();
                return ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL, token.getLexeme());
            case idTok:
                stream.advance();
                if (stream.match(TokenType.period)) {
                    Token column = stream.expect(TokenType.idTok);
                    return ExpressionNode.column(token.getLexeme(), column.getLexeme());
                }
                return ExpressionNode.column(null, token.getLexeme());
            case lparent:
                stream.advance();
                ExpressionNode inner = parseLogicalAnd(stream);
                stream.expect(TokenType.rparent);
                return inner;
            default:
                throw syntaxError(stream, "Expected a literal, column reference, or '(' but found "
                    + token.getType() + " ('" + token.getLexeme() + "')");
        }
    }

    private boolean isComparison(TokenType type) {
        return switch (type) {
            case eql, neq, gtr, geq, lss, leq -> true;
            default -> false;
        };
    }

    private boolean isPredicate(ExpressionNode node) {
        return node.getType() == ExpressionNode.Type.COMPARISON
            || node.getType() == ExpressionNode.Type.LOGICAL_AND;
    }

    private void requireValue(ExpressionNode node, Token token) {
        if (isPredicate(node)) {
            throw syntaxError("Expected a value expression, but found a condition", token);
        }
    }

    private void requirePredicate(ExpressionNode node, Token token) {
        if (!isPredicate(node)) {
            throw syntaxError("Expected a comparison or a condition joined by AND", token);
        }
    }

    private void expectBoundary(TokenStream stream, boolean valueExpression) {
        TokenType type = stream.peek().getType();
        boolean boundary = switch (type) {
            case EOF, semicolon, comma, rparent, fromTok, whereTok -> true;
            default -> isContextualClause(stream);
        };
        // A comparison parser can resume after a value
        if (boundary || (valueExpression && (isComparison(type) || type == TokenType.andTok))) {
            return;
        }
        throw syntaxError(stream, "Unexpected token after " + (valueExpression ? "value expression" : "condition")
            + ": '" + stream.peek().getLexeme() + "'");
    }

    private boolean isContextualClause(TokenStream stream) {
        // These clause keywords are still identifiers in the shared lexer.
        return matchesKeywords(stream, "ORDER", "BY") || matchesKeywords(stream, "JOIN")
            || matchesKeywords(stream, "NATURAL", "JOIN");
    }

    private boolean matchesKeywords(TokenStream stream, String... keywords) {
        for (int i = 0; i < keywords.length; i++) {
            int position = stream.getPosition() + i;
            if (position >= stream.getTokens().size()) {
                return false;
            }
            Token token = stream.getTokens().get(position);
            if (token.getType() != TokenType.idTok || !keywords[i].equalsIgnoreCase(token.getLexeme())) {
                return false;
            }
        }
        return true;
    }

    private QuerySyntaxException syntaxError(TokenStream stream, String message) {
        return syntaxError(message, stream.peek());
    }

    private QuerySyntaxException syntaxError(String message, Token token) {
        QuerySyntaxException exception = new QuerySyntaxException(message, token, sqlSource);
        if (errorCollector != null) {
            errorCollector.addException(exception);
        }
        return exception;
    }
}
