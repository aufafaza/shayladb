package mdbms.optimizer;

import java.util.List;

public class TokenStream {
    private final List<Token> tokens;
    private int position;

    public TokenStream(List<Token> tokens) {
        this.tokens = tokens;
    }

    public Token peek() {
        // TODO
        return null;
    }

    public Token advance() {
        // TODO
        return null;
    }

    public boolean match(TokenType type) {
        // TODO
        return false;
    }

    public Token expect(TokenType type) {
        // TODO
        return null;
    }

    public boolean isAtEnd() {
        // TODO
        return false;
    }
}
