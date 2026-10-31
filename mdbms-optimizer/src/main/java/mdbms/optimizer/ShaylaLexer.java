package mdbms.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ShaylaLexer {
    private String input;
    private int length;
    private int pos;
    private int line;
    private int col;
    private QueryErrorCollector errorCollector;

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        Map.entry("SELECT", TokenType.selectTok),
        Map.entry("FROM", TokenType.fromTok),
        Map.entry("WHERE", TokenType.whereTok),
        Map.entry("INSERT", TokenType.insertTok),
        Map.entry("INTO", TokenType.intoTok),
        Map.entry("VALUES", TokenType.valuesTok),
        Map.entry("DELETE", TokenType.deleteTok),
        Map.entry("UPDATE", TokenType.updateTok),
        Map.entry("SET", TokenType.setTok),
        Map.entry("CREATE", TokenType.createTok),
        Map.entry("TABLE", TokenType.tableTok),
        Map.entry("VIEW", TokenType.viewTok),
        Map.entry("AS", TokenType.asTok),
        Map.entry("INDEX", TokenType.indexTok),
        Map.entry("ON", TokenType.onTok),
        Map.entry("INT", TokenType.intTypeTok),
        Map.entry("INTEGER", TokenType.intTypeTok),
        Map.entry("VARCHAR", TokenType.varcharTok),
        Map.entry("IF", TokenType.ifTok),
        Map.entry("EXISTS", TokenType.existsTok),
        Map.entry("NULL", TokenType.nullTok),
        Map.entry("PRIMARY", TokenType.primaryTok),
        Map.entry("KEY", TokenType.keyTok),
        Map.entry("START", TokenType.startTok),
        Map.entry("TRANSACTION", TokenType.transactionTok),
        Map.entry("BEGIN", TokenType.beginTok),
        Map.entry("COMMIT", TokenType.commitTok),
        Map.entry("ROLLBACK", TokenType.rollbackTok),
        Map.entry("NOT", TokenType.notTok),
        Map.entry("AND", TokenType.andTok),
        Map.entry("OR", TokenType.orTok),
        Map.entry("DIV", TokenType.idiv),
        Map.entry("MOD", TokenType.imod)
    );

    public ShaylaLexer(String input) {
        this(input, null);
    }

    public ShaylaLexer(String input, QueryErrorCollector errorCollector) {
        this.input = input != null ? input : "";
        this.length = this.input.length();
        this.pos = 0;
        this.line = 1;
        this.col = 1;
        this.errorCollector = errorCollector;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input != null ? input : "";
        this.length = this.input.length();
        this.pos = 0;
        this.line = 1;
        this.col = 1;
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

    public QueryErrorCollector getErrorCollector() {
        return errorCollector;
    }

    public void setErrorCollector(QueryErrorCollector errorCollector) {
        this.errorCollector = errorCollector;
    }

    public static Map<String, TokenType> getKeywords() {
        return KEYWORDS;
    }

    public List<Token> tokenize() {
        this.pos = 0;
        this.line = 1;
        this.col = 1;
        List<Token> tokens = new ArrayList<>();

        while (pos < length) {
            consumeWhitespace();
            if (pos >= length) {
                break;
            }

            if (peek(0) == '-' && peek(1) == '-') {
                skipSingleLineComment();
                continue;
            }

            int startLine = line;
            int startCol = col;
            char current = peek(0);

            if (Character.isLetter(current) || current == '_') {
                tokens.add(readIdentifierOrKeyword(startLine, startCol));
            } else if (Character.isDigit(current)) {
                tokens.add(readNumber(startLine, startCol));
            } else if (current == '\'' || current == '"') {
                tokens.add(readString(startLine, startCol));
            } else {
                tokens.add(readSymbolOrOperator(startLine, startCol));
            }
        }

        tokens.add(new Token(TokenType.EOF, "", line, col));
        return tokens;
    }

    public TokenStream tokenizeToStream() {
        return new TokenStream(tokenize(), input, errorCollector);
    }

    private Token readIdentifierOrKeyword(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (pos < length && (Character.isLetterOrDigit(peek(0)) || peek(0) == '_')) {
            sb.append(advance());
        }
        String rawLexeme = sb.toString();
        String upperLexeme = rawLexeme.toUpperCase(Locale.ROOT);
        TokenType type = KEYWORDS.getOrDefault(upperLexeme, TokenType.idTok);
        String lexeme = (type == TokenType.idTok) ? rawLexeme : upperLexeme;
        return new Token(type, lexeme, startLine, startCol);
    }

    private Token readNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        boolean isReal = false;

        while (pos < length && Character.isDigit(peek(0))) {
            sb.append(advance());
        }

        if (pos < length && peek(0) == '.') {
            isReal = true;
            sb.append(advance());
            if (pos >= length || !Character.isDigit(peek(0))) {
                throwLexicalError("Malformed number literal: '" + sb + "'", startLine, startCol, sb.toString());
            }
            while (pos < length && Character.isDigit(peek(0))) {
                sb.append(advance());
            }
        }

        if (pos < length && (peek(0) == '.' || Character.isLetter(peek(0)) || peek(0) == '_')) {
            while (pos < length && (Character.isLetterOrDigit(peek(0)) || peek(0) == '.' || peek(0) == '_')) {
                sb.append(advance());
            }
            throwLexicalError("Malformed number literal: '" + sb + "'", startLine, startCol, sb.toString());
        }

        TokenType type = isReal ? TokenType.realLitTok : TokenType.intLitTok;
        return new Token(type, sb.toString(), startLine, startCol);
    }

    private Token readString(int startLine, int startCol) {
        char quote = advance();
        StringBuilder sb = new StringBuilder();

        while (pos < length) {
            char current = peek(0);
            if (current == quote) {
                advance();
                if (pos < length && peek(0) == quote) {
                    sb.append(advance());
                    continue;
                }
                return new Token(TokenType.strTok, sb.toString(), startLine, startCol);
            }
            if (current == '\\') {
                advance();
                if (pos >= length) {
                    break;
                }
                char escaped = advance();
                switch (escaped) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case '\\':
                    case '\'':
                    case '"':
                        sb.append(escaped);
                        break;
                    default:
                        sb.append(escaped);
                        break;
                }
                continue;
            }
            sb.append(advance());
        }

        String rawLexeme = quote + sb.toString();
        throwLexicalError("Unterminated string literal", startLine, startCol, rawLexeme);
        return null;
    }

    private Token readSymbolOrOperator(int startLine, int startCol) {
        char ch = advance();
        switch (ch) {
            case '+':
                return new Token(TokenType.plus, "+", startLine, startCol);
            case '-':
                return new Token(TokenType.minus, "-", startLine, startCol);
            case '*':
                return new Token(TokenType.star, "*", startLine, startCol);
            case '/':
                return new Token(TokenType.rdiv, "/", startLine, startCol);
            case '%':
                return new Token(TokenType.imod, "%", startLine, startCol);
            case '=':
                return new Token(TokenType.eql, "=", startLine, startCol);
            case '!':
                if (peek(0) == '=') {
                    advance();
                    return new Token(TokenType.neq, "!=", startLine, startCol);
                }
                return createUnknownToken(String.valueOf(ch), startLine, startCol);
            case '<':
                if (peek(0) == '=') {
                    advance();
                    return new Token(TokenType.leq, "<=", startLine, startCol);
                }
                if (peek(0) == '>') {
                    advance();
                    return new Token(TokenType.neq, "<>", startLine, startCol);
                }
                return new Token(TokenType.lss, "<", startLine, startCol);
            case '>':
                if (peek(0) == '=') {
                    advance();
                    return new Token(TokenType.geq, ">=", startLine, startCol);
                }
                return new Token(TokenType.gtr, ">", startLine, startCol);
            case '(':
                return new Token(TokenType.lparent, "(", startLine, startCol);
            case ')':
                return new Token(TokenType.rparent, ")", startLine, startCol);
            case ',':
                return new Token(TokenType.comma, ",", startLine, startCol);
            case ';':
                return new Token(TokenType.semicolon, ";", startLine, startCol);
            case '.':
                return new Token(TokenType.period, ".", startLine, startCol);
            case ':':
                return new Token(TokenType.colon, ":", startLine, startCol);
            default:
                return createUnknownToken(String.valueOf(ch), startLine, startCol);
        }
    }

    private Token createUnknownToken(String lexeme, int startLine, int startCol) {
        Token token = new Token(TokenType.UNKNOWN, lexeme, startLine, startCol);
        if (errorCollector != null) {
            errorCollector.addException(
                new QueryLexicalException("Unknown symbol: '" + lexeme + "'", token, input)
            );
        }
        return token;
    }

    private void throwLexicalError(String message, int startLine, int startCol, String lexeme) {
        QueryLexicalException exception = new QueryLexicalException(
            message, startLine, startCol, lexeme, input
        );
        if (errorCollector != null) {
            errorCollector.addException(exception);
        }
        throw exception;
    }

    private void consumeWhitespace() {
        while (pos < length && Character.isWhitespace(peek(0))) {
            advance();
        }
    }

    private void skipSingleLineComment() {
        while (pos < length && peek(0) != '\n' && peek(0) != '\r') {
            advance();
        }
    }

    private char peek(int offset) {
        int index = pos + offset;
        if (index < 0 || index >= length) {
            return '\0';
        }
        return input.charAt(index);
    }

    private char advance() {
        if (pos >= length) {
            return '\0';
        }
        char current = input.charAt(pos++);
        if (current == '\n') {
            line++;
            col = 1;
        } else if (current == '\r') {
            if (pos >= length || input.charAt(pos) != '\n') {
                line++;
                col = 1;
            }
        } else {
            col++;
        }
        return current;
    }
}
