package mdbms.storage.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.ForeignKeyAction;
import mdbms.models.schema.ForeignKeyConstraint;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

// parser subset SQL DDL buat bootstrap schema, cuma nerima CREATE TABLE dan CREATE INDEX, sisanya ditolak jelas
// keyword ga peka huruf besar kecil, nama tabel kolom dan index disimpan apa adanya sama kayak lexer QO
public final class SchemaScriptParser {
    private final List<Token> tokens;
    private int position;

    private SchemaScriptParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    // parse seluruh isi file schema, error selalu bawa nomor baris dan kolom
    public static SchemaScript parse(String source) {
        if (source == null) throw error(1, 1, "Schema script must not be null");
        return new SchemaScriptParser(tokenize(source)).script();
    }

    private SchemaScript script() {
        List<SchemaScript.TableDefinition> tables = new ArrayList<>();
        List<SchemaScript.IndexDefinition> indexes = new ArrayList<>();
        while (!atEnd()) {
            if (peekSymbol(";")) {
                position++;
                continue;
            }
            Token start = peek();
            if (!isKeyword(start, "CREATE")) {
                throw error(start, "Unsupported statement '" + start.text
                        + "', schema scripts only allow CREATE TABLE and CREATE INDEX");
            }
            position++;
            Token kind = next("TABLE or INDEX after CREATE");
            if (isKeyword(kind, "TABLE")) {
                tables.add(new SchemaScript.TableDefinition(createTable(), start.line));
            } else if (isKeyword(kind, "INDEX")) {
                indexes.add(new SchemaScript.IndexDefinition(createIndex(), start.line));
            } else {
                throw error(kind, "Unsupported statement 'CREATE " + kind.text
                        + "', schema scripts only allow CREATE TABLE and CREATE INDEX");
            }
            if (!atEnd()) expectSymbol(";");
        }
        return new SchemaScript(tables, indexes);
    }

    // CREATE TABLE nama ( kolom, ..., PRIMARY KEY (...), FOREIGN KEY (...) REFERENCES ... )
    private TableSchema createTable() {
        String table = identifier("table name");
        expectSymbol("(");
        List<ColumnSpec> columns = new ArrayList<>();
        List<String> primaryKey = null;
        Token primaryKeyToken = null;
        List<ForeignKeyConstraint> foreignKeys = new ArrayList<>();
        do {
            Token element = peek();
            if (isKeyword(element, "PRIMARY")) {
                position++;
                expectKeyword("KEY");
                if (primaryKey != null) throw error(element, "Table " + table + " declares PRIMARY KEY more than once");
                primaryKey = identifierList();
                primaryKeyToken = element;
            } else if (isKeyword(element, "FOREIGN")) {
                position++;
                foreignKeys.add(foreignKey());
            } else {
                ColumnSpec column = column();
                if (column.primaryKey) {
                    if (primaryKey != null) throw error(element, "Table " + table + " declares PRIMARY KEY more than once");
                    primaryKey = List.of(column.name);
                    primaryKeyToken = element;
                }
                columns.add(column);
            }
        } while (acceptSymbol(","));
        expectSymbol(")");

        if (primaryKey == null) primaryKey = List.of();
        Set<String> keyColumns = new LinkedHashSet<>(primaryKey);
        if (keyColumns.size() != primaryKey.size()) {
            throw error(primaryKeyToken, "PRIMARY KEY of " + table + " lists a column more than once");
        }
        List<ColumnDefinition> definitions = new ArrayList<>();
        for (ColumnSpec column : columns) {
            boolean inKey = keyColumns.contains(column.name);
            if (inKey && column.explicitNull) {
                throw error(column.token, "Primary key column " + column.name + " cannot be NULL");
            }
            definitions.add(new ColumnDefinition(column.name, column.type, column.length, column.nullable && !inKey));
        }
        return new TableSchema(table, List.copyOf(definitions), List.copyOf(primaryKey), List.copyOf(foreignKeys));
    }

    // nama TIPE [(panjang)] lalu NOT NULL, NULL, atau PRIMARY KEY dengan urutan bebas
    private ColumnSpec column() {
        Token nameToken = peek();
        String name = identifier("column name");
        Token typeToken = next("column type");
        DataType type = switch (typeToken.text.toUpperCase(Locale.ROOT)) {
            case "INTEGER", "INT" -> DataType.INTEGER;
            case "FLOAT" -> DataType.FLOAT;
            case "CHAR" -> DataType.CHAR;
            case "VARCHAR" -> DataType.VARCHAR;
            default -> throw error(typeToken, "Unsupported type '" + typeToken.text
                    + "' for column " + name + ", use INTEGER, FLOAT, CHAR(n), or VARCHAR(n)");
        };
        int length = 0;
        boolean text = type == DataType.CHAR || type == DataType.VARCHAR;
        if (acceptSymbol("(")) {
            if (!text) throw error(typeToken, type + " column " + name + " does not take a length");
            Token number = next("length");
            if (number.kind != Kind.NUMBER) throw error(number, "Expected a positive length for column " + name);
            try {
                length = Integer.parseInt(number.text);
            } catch (NumberFormatException e) {
                throw error(number, "Length of column " + name + " is too large");
            }
            if (length <= 0) throw error(number, "Length of column " + name + " must be positive");
            expectSymbol(")");
        } else if (text) {
            throw error(typeToken, type + " column " + name + " needs a length, for example " + type + "(20)");
        }

        ColumnSpec spec = new ColumnSpec(nameToken, name, type, length);
        while (true) {
            Token modifier = peek();
            if (isKeyword(modifier, "NOT")) {
                position++;
                expectKeyword("NULL");
                if (spec.explicitNull) throw error(modifier, "Column " + name + " cannot be both NULL and NOT NULL");
                spec.nullable = false;
            } else if (isKeyword(modifier, "NULL")) {
                position++;
                if (!spec.nullable) throw error(modifier, "Column " + name + " cannot be both NULL and NOT NULL");
                spec.explicitNull = true;
            } else if (isKeyword(modifier, "PRIMARY")) {
                position++;
                expectKeyword("KEY");
                spec.primaryKey = true;
            } else if (modifier.kind == Kind.WORD) {
                throw error(modifier, "Unsupported column option '" + modifier.text + "' for column " + name
                        + ", only NOT NULL, NULL, and PRIMARY KEY are allowed");
            } else {
                return spec;
            }
        }
    }

    // FOREIGN KEY (kolom) REFERENCES tabel(kolom) [ON DELETE aksi] [ON UPDATE aksi], default nya NO ACTION
    private ForeignKeyConstraint foreignKey() {
        expectKeyword("KEY");
        expectSymbol("(");
        String column = identifier("foreign key column");
        expectSymbol(")");
        expectKeyword("REFERENCES");
        String referencedTable = identifier("referenced table");
        expectSymbol("(");
        String referencedColumn = identifier("referenced column");
        expectSymbol(")");
        ForeignKeyAction onDelete = null;
        ForeignKeyAction onUpdate = null;
        while (isKeyword(peek(), "ON")) {
            position++;
            Token event = next("DELETE or UPDATE after ON");
            if (isKeyword(event, "DELETE")) {
                if (onDelete != null) throw error(event, "ON DELETE is declared more than once");
                onDelete = action();
            } else if (isKeyword(event, "UPDATE")) {
                if (onUpdate != null) throw error(event, "ON UPDATE is declared more than once");
                onUpdate = action();
            } else {
                throw error(event, "Expected DELETE or UPDATE after ON");
            }
        }
        return new ForeignKeyConstraint(column, referencedTable, referencedColumn,
                onDelete == null ? ForeignKeyAction.NO_ACTION : onDelete,
                onUpdate == null ? ForeignKeyAction.NO_ACTION : onUpdate);
    }

    private ForeignKeyAction action() {
        Token token = next("CASCADE, RESTRICT, SET NULL, or NO ACTION");
        String word = token.text.toUpperCase(Locale.ROOT);
        return switch (word) {
            case "CASCADE" -> ForeignKeyAction.CASCADE;
            case "RESTRICT" -> ForeignKeyAction.RESTRICT;
            case "SET" -> {
                expectKeyword("NULL");
                yield ForeignKeyAction.SET_NULL;
            }
            case "NO" -> {
                expectKeyword("ACTION");
                yield ForeignKeyAction.NO_ACTION;
            }
            default -> throw error(token, "Expected CASCADE, RESTRICT, SET NULL, or NO ACTION");
        };
    }

    // CREATE INDEX nama ON tabel(kolom), cuma satu kolom karena index SM satu kolom
    private IndexInfo createIndex() {
        String name = identifier("index name");
        expectKeyword("ON");
        String table = identifier("table name");
        expectSymbol("(");
        String column = identifier("indexed column");
        if (peekSymbol(",")) throw error(peek(), "Indexes cover exactly one column");
        expectSymbol(")");
        return new IndexInfo(name, table, column, IndexType.BPLUS_TREE);
    }

    private List<String> identifierList() {
        expectSymbol("(");
        List<String> names = new ArrayList<>();
        do {
            names.add(identifier("column name"));
        } while (acceptSymbol(","));
        expectSymbol(")");
        return names;
    }

    private String identifier(String what) {
        Token token = next(what);
        if (token.kind != Kind.WORD) throw error(token, "Expected " + what + " but found '" + token.text + "'");
        return token.text;
    }

    private void expectKeyword(String keyword) {
        Token token = next(keyword);
        if (!isKeyword(token, keyword)) throw error(token, "Expected " + keyword + " but found '" + token.text + "'");
    }

    private void expectSymbol(String symbol) {
        Token token = next("'" + symbol + "'");
        if (token.kind != Kind.SYMBOL || !token.text.equals(symbol)) {
            throw error(token, "Expected '" + symbol + "' but found '" + token.text + "'");
        }
    }

    private boolean acceptSymbol(String symbol) {
        if (!peekSymbol(symbol)) return false;
        position++;
        return true;
    }

    private boolean peekSymbol(String symbol) {
        Token token = peek();
        return token.kind == Kind.SYMBOL && token.text.equals(symbol);
    }

    private static boolean isKeyword(Token token, String keyword) {
        return token.kind == Kind.WORD && token.text.equalsIgnoreCase(keyword);
    }

    private Token next(String what) {
        Token token = peek();
        if (token.kind == Kind.END) throw error(token, "Unexpected end of script, expected " + what);
        position++;
        return token;
    }

    private Token peek() {
        return tokens.get(position);
    }

    private boolean atEnd() {
        return peek().kind == Kind.END;
    }

    // pecah teks jadi kata, angka, dan simbol, komentar diawali dua tanda minus sampai akhir baris
    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int line = 1;
        int column = 1;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                column = 1;
                i++;
            } else if (Character.isWhitespace(c)) {
                column++;
                i++;
            } else if (c == '-' && i + 1 < source.length() && source.charAt(i + 1) == '-') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
                tokens.add(new Token(Kind.WORD, source.substring(start, i), line, column));
                column += i - start;
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < source.length() && Character.isDigit(source.charAt(i))) i++;
                tokens.add(new Token(Kind.NUMBER, source.substring(start, i), line, column));
                column += i - start;
            } else if (c == '(' || c == ')' || c == ',' || c == ';') {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), line, column));
                column++;
                i++;
            } else {
                throw error(line, column, "Unexpected character '" + c + "'");
            }
        }
        tokens.add(new Token(Kind.END, "end of script", line, column));
        return tokens;
    }

    private static StorageException error(Token token, String message) {
        return error(token.line, token.column, message);
    }

    private static StorageException error(int line, int column, String message) {
        return new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                "Schema script line " + line + ", column " + column + ": " + message);
    }

    private enum Kind { WORD, NUMBER, SYMBOL, END }

    private record Token(Kind kind, String text, int line, int column) {}

    // kolom yang lagi di parse, flag null dicatat terpisah biar NULL di kolom primary key bisa ditolak
    private static final class ColumnSpec {
        private final Token token;
        private final String name;
        private final DataType type;
        private final int length;
        private boolean nullable = true;
        private boolean explicitNull;
        private boolean primaryKey;

        private ColumnSpec(Token token, String name, DataType type, int length) {
            this.token = token;
            this.name = name;
            this.type = type;
            this.length = length;
        }
    }
}
