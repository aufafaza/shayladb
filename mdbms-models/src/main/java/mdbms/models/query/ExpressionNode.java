package mdbms.models.query;

import java.util.Objects;

/** A literal, column reference, or binary arithmetic expression. */
public class ExpressionNode {
    public enum Type {
        INTEGER_LITERAL,
        REAL_LITERAL,
        STRING_LITERAL,
        COLUMN_REFERENCE,
        BINARY_OPERATION
    }

    private final Type type;
    private final String value;
    private final String tableName;
    private final ExpressionNode left;
    private final ExpressionNode right;

    private ExpressionNode(Type type, String value, String tableName) {
        this(type, value, tableName, null, null);
    }

    private ExpressionNode(Type type, String value, String tableName,
                           ExpressionNode left, ExpressionNode right) {
        this.type = Objects.requireNonNull(type);
        this.value = Objects.requireNonNull(value);
        this.tableName = tableName;
        this.left = left;
        this.right = right;
    }

    public static ExpressionNode literal(Type type, String value) {
        Objects.requireNonNull(type);
        if (type != Type.INTEGER_LITERAL && type != Type.REAL_LITERAL && type != Type.STRING_LITERAL) {
            throw new IllegalArgumentException("Expected a literal node type");
        }
        return new ExpressionNode(type, value, null);
    }

    public static ExpressionNode column(String tableName, String columnName) {
        return new ExpressionNode(Type.COLUMN_REFERENCE, columnName, tableName);
    }

    public static ExpressionNode binary(String operator, ExpressionNode left, ExpressionNode right) {
        if (!"+".equals(operator) && !"-".equals(operator)
            && !"*".equals(operator) && !"/".equals(operator)) {
            throw new IllegalArgumentException("Unsupported arithmetic operator: " + operator);
        }
        return new ExpressionNode(Type.BINARY_OPERATION, operator, null,
            Objects.requireNonNull(left), Objects.requireNonNull(right));
    }

    public Type getType() {
        return type;
    }

    /** The literal text, column name, or operator. note that numeric literals retain their lexeme. */
    public String getValue() {
        return value;
    }

    /** Returns the optional table qualifier, or null for unqualified columns/literals. */
    public String getTableName() {
        return tableName;
    }

    public ExpressionNode getLeft() {
        return left;
    }

    public ExpressionNode getRight() {
        return right;
    }
}
