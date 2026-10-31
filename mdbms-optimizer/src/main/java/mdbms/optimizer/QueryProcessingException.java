package mdbms.optimizer;

public class QueryProcessingException extends RuntimeException {
    private String input;
    private int length;
    private int pos;
    private ErrorCategory category;
    private int line;
    private int column;
    private String lexeme;
    private String sqlSource;

    public QueryProcessingException(ErrorCategory category, String message, Token token, String sqlSource) {
        this(category, message, token.getLine(), token.getColumn(), token.getLexeme(), sqlSource);
    }

    public QueryProcessingException(
        ErrorCategory category,
        String message,
        int line,
        int column,
        String lexeme,
        String sqlSource
    ) {
        super(message);
        this.category = category;
        this.line = line;
        this.column = column;
        this.lexeme = lexeme;
        this.sqlSource = sqlSource;
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

    public ErrorCategory getCategory() {
        return category;
    }

    public void setCategory(ErrorCategory category) {
        this.category = category;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public String getLexeme() {
        return lexeme;
    }

    public void setLexeme(String lexeme) {
        this.lexeme = lexeme;
    }

    public String getSqlSource() {
        return sqlSource;
    }

    public void setSqlSource(String sqlSource) {
        this.sqlSource = sqlSource;
    }

    public String getFormattedError() {
        return "[" + category + "] " + this.getMessage() + " pada baris " +
        this.line + ", kolom " + this.column;
    } 
}
