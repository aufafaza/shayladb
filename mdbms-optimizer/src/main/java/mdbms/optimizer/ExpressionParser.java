package mdbms.optimizer;

import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;

public class ExpressionParser {
    /** Reads an arithmetic value expression; the caller owns following clause/delimiter tokens. */
    public ExpressionTree parseExpression(TokenStream stream) {
        return new ExpressionTree(parseAdditive(stream));
    }

    public ExpressionTree parsePredicate(TokenStream stream) {
        // TODO
        return null;
    }

    private ExpressionNode parseLogicalAnd(TokenStream stream) {
        // TODO
        return null;
    }

    private ExpressionNode parseComparison(TokenStream stream) {
        // TODO
        return null;
    }

    private ExpressionNode parseAdditive(TokenStream stream) {
        // Each operand includes its higher-priority multiplication/division first.
        ExpressionNode left = parseMultiplicative(stream);
        while (stream.peek().getType() == TokenType.plus || stream.peek().getType() == TokenType.minus) {
            Token operator = stream.advance();
            ExpressionNode right = parseMultiplicative(stream);
            // reuse the previous tree on the left (ex. 5 - 3 - 2 mean (5 - 3) - 2.)
            left = ExpressionNode.binary(operator.getLexeme(), left, right);
        }
        return left;
    }

    private ExpressionNode parseMultiplicative(TokenStream stream) {
        ExpressionNode left = parseUnary(stream);
        while (stream.peek().getType() == TokenType.star || stream.peek().getType() == TokenType.rdiv) {
            Token operator = stream.advance();
            ExpressionNode right = parseUnary(stream);
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
                ExpressionTree inner = parseExpression(stream);
                stream.expect(TokenType.rparent);
                return inner.getRoot();
            default:
                throw stream.syntaxError("Expected a literal, column reference, or '(' but found "
                    + token.getType() + " ('" + token.getLexeme() + "')");
        }
    }
}
