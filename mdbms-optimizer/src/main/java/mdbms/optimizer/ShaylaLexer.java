package mdbms.optimizer;

import java.util.List;
import java.util.Map;

public class ShaylaLexer {
    private String input;
    private int length;
    private int pos;
    private int line;
    private int col;
    private static final Map<String, TokenType> KEYWORDS = Map.of();

    public ShaylaLexer(String input) {
        this.input = input;
        this.length = input.length();
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getPos() {
        return pos;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getCol() {
        return col;
    }

    public void setCol(int col) {
        this.col = col;
    }

    public static Map<String, TokenType> getKeywords() {
        return KEYWORDS;
    }

    public List<Token> tokenize() {
        // TODO
        return List.of();
    }

    private Token readIdentifierOrKeyword(int startLine, int startCol) {
        // TODO
        return null;
    }

    private Token readNumber(int startLine, int startCol) {
        // TODO
        return null;
    }

    private Token readString(int startLine, int startCol) {
        // TODO
        return null;
    }

    private Token readSymbolOrOperator(int startLine, int startCol) {
        // TODO
        return null;
    }

    private void consumeWhitespace() {
        // TODO
    }

    private void skipSingleLineComment() {
        // TODO
    }

    private char peek(int offset) {
        // TODO
        return '\0';
    }

    private char advance() {
        // TODO
        return '\0';
    }
}
