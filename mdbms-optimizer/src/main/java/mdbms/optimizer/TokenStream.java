package mdbms.optimizer;

import java.util.List;

public class TokenStream {
    private final List<Token> tokens;
    private int position;
    private final String sqlSource;
    private final QueryErrorCollector errorCollector;

    public TokenStream(List<Token> tokens) {
        this(tokens, null, null);
    }

    public TokenStream(List<Token> tokens, String sqlSource) {
        this(tokens, sqlSource, null);
    }

    public TokenStream(List<Token> tokens, String sqlSource, QueryErrorCollector errorCollector) {
        this.tokens = tokens != null ? List.copyOf(tokens) : List.of();
        this.position = 0;
        this.sqlSource = sqlSource;
        this.errorCollector = errorCollector;
    }

    public List<Token> getTokens() {
        return tokens;
    }

    public int getPosition() {
        return position;
    }

    public Token peek() {
        if (tokens.isEmpty()) {
            return new Token(TokenType.EOF, "", 1, 1);
        }
        if (position >= tokens.size()) {
            Token last = tokens.get(tokens.size() - 1);
            if (last.getType() == TokenType.EOF) {
                return last;
            }
            return new Token(TokenType.EOF, "", last.getLine(), last.getColumn());
        }
        return tokens.get(position);
    }

    public Token advance() {
        Token current = peek();
        if (position < tokens.size() && current.getType() != TokenType.EOF) {
            position++;
        }
        return current;
    }

    public boolean match(TokenType type) {
        if (peek().getType() == type) {
            advance();
            return true;
        }
        return false;
    }

    public Token expect(TokenType type) {
        Token current = peek();
        if (current.getType() == type) {
            return advance();
        }
        QuerySyntaxException exception = new QuerySyntaxException(
            "Expected token " + type + " but found " + current.getType() + " ('" + current.getLexeme() + "')",
            current,
            sqlSource
        );
        if (errorCollector != null) {
            errorCollector.addException(exception);
        }
        throw exception;
    }

    public boolean isAtEnd() {
        return position >= tokens.size() || peek().getType() == TokenType.EOF;
    }

    public void printAllParsedToken(){
        if (tokens.isEmpty() && tokens.get(0).getType() == TokenType.EOF){
            System.out.println("Tidak ada Tokens");
        }else{
            for (Token token : tokens) {
                System.out.println("Dikenali " + "\""+token.getLexeme() + "\"" + " dengan tipe " + token.getType().toString() + " pada baris " + token.getLine() + ", kolom " + token.getColumn());
            }
        }
    }
}
