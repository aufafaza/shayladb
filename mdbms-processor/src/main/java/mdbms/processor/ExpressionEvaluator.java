package mdbms.processor;

import java.util.List;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.exception.UnexpectedTypeException;

public class ExpressionEvaluator {
    public static Object evaluate(ExpressionTree expressionTree, IResultRow... rows) {
        ExpressionNode root = expressionTree.getRoot();
        IResultRow row = combineRow(rows);
        return ExpressionEvaluator.evaluate(root, row);
    }

    private static Object evaluate(ExpressionNode expressionNode, IResultRow row) {
        switch (expressionNode.getType()) {
            // Literals
            case STRING_LITERAL:
                return expressionNode.getValue();
            case INTEGER_LITERAL:
                return Long.parseLong(expressionNode.getValue());
            case REAL_LITERAL:
                return Double.valueOf(expressionNode.getValue());

            // Accessing column
            case COLUMN_REFERENCE:
                return columnReference(expressionNode, row);

            case BINARY_OPERATION:
                return ExpressionEvaluator.arithmeticOperation(expressionNode, row);

            case COMPARISON:
                return ExpressionEvaluator.comparisonOperation(expressionNode, row);

            case LOGICAL_AND:
                return ExpressionEvaluator.logicalAnd(expressionNode, row);

            // TODO: operasi logical OR dan DIV kalo dah ada sebagai NodeType bisa
            default:
                return null;
        }
    }

    public static boolean matches(
            List<ExpressionTree> conditions,
            IResultRow... rows) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        IResultRow row = combineRow(rows);

        for (ExpressionTree condition : conditions) {
            if (condition == null || condition.getRoot() == null) {
                continue;
            }

            Object result = evaluate(condition, row);
            if (result instanceof String) {
                throw new UnexpectedTypeException("BOOLEAN", "STRING");
            }
            if (result instanceof Long) {
                throw new UnexpectedTypeException("BOOLEAN", "INTEGER");
            }
            if (result instanceof Double) {
                throw new UnexpectedTypeException("BOOLEAN", "REAL");
            }

            if (!Boolean.TRUE.equals(result)) {
                return false;
            }
        }

        return true;
    }

    private static IResultRow combineRow(IResultRow... rows) {
        if (rows.length == 0)
            return ResultRow.EMPTY;
        if (rows.length == 1)
            return rows[0];

        ResultTableSchema combinedSchema = new ResultTableSchema();

        for (IResultRow row : rows) {
            ResultTableSchema schema = row.getSchema();
            for (ColumnDefinition columnDefinition : schema.getColumns()) {
                combinedSchema.addColumn(schema.getColumnTableOrigin(columnDefinition), columnDefinition);
            }
        }

        // tiap baris cuma mengisi kolomnya sendiri, biar nilai baris lain tidak tertimpa null
        ResultRow combinedRow = new ResultRow(combinedSchema);
        for (IResultRow row : rows) {
            ResultTableSchema schema = row.getSchema();
            for (ColumnDefinition columnDefinition : schema.getColumns()) {
                String tableName = schema.getColumnTableOrigin(columnDefinition);
                String columnName = columnDefinition.name();
                combinedRow.set(tableName, columnName, row.get(tableName, columnName));
            }
        }
        return combinedRow;
    }

    private static Object columnReference(ExpressionNode expressionNode, IResultRow row) {
        String tableName = expressionNode.getTableName();
        String columnName = expressionNode.getValue();
        if (tableName == null) {
            return row.get(columnName);
        } else {
            return row.get(tableName, columnName);
        }
    }

    private static Object arithmeticOperation(ExpressionNode expressionNode, IResultRow row) {
        Object leftValue = ExpressionEvaluator.evaluate(expressionNode.getLeft(), row);
        Object rightValue = ExpressionEvaluator.evaluate(expressionNode.getRight(), row);
        switch (expressionNode.getValue()) {
            case "+":
                if (leftValue instanceof String leftString && rightValue instanceof String rightString) {
                    return leftString + rightString;
                } else if (isInteger(leftValue) && isInteger(rightValue)) {
                    return ((Number) leftValue).longValue() + ((Number) rightValue).longValue();
                } else if (leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
                    return leftNumber.doubleValue() + rightNumber.doubleValue();
                } else {
                    throw new RuntimeException("Failed to evaluate expression: invalid plus operation");
                }

            case "-":
                if (isInteger(leftValue) && isInteger(rightValue)) {
                    return ((Number) leftValue).longValue() - ((Number) rightValue).longValue();
                } else if (leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
                    return leftNumber.doubleValue() - rightNumber.doubleValue();
                } else {
                    throw new RuntimeException("Failed to evaluate expression: invalid minus operation");
                }

            case "*":
                if (isInteger(leftValue) && isInteger(rightValue)) {
                    return ((Number) leftValue).longValue() * ((Number) rightValue).longValue();
                } else if (leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
                    return leftNumber.doubleValue() * rightNumber.doubleValue();
                } else {
                    throw new RuntimeException("Failed to evaluate expression: invalid multiply operation");
                }

            case "/":
                if (leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
                    return leftNumber.doubleValue() / rightNumber.doubleValue();
                } else {
                    throw new RuntimeException("Failed to evaluate expression: invalid divide operation");
                }

            default:
                throw new RuntimeException(
                        "Failed to evaluate expression: unknown arithmetic operator " + expressionNode.getValue());
        }
    }

    private static Object comparisonOperation(ExpressionNode expressionNode, IResultRow row) {
        Object leftValue = ExpressionEvaluator.evaluate(expressionNode.getLeft(), row);
        Object rightValue = ExpressionEvaluator.evaluate(expressionNode.getRight(), row);
        int comparison = compare(leftValue, rightValue);
        switch (expressionNode.getValue()) {
            case "=":
                return comparison == 0;
            case "<>":
                return comparison != 0;
            case ">":
                return comparison > 0;
            case ">=":
                return comparison >= 0;
            case "<":
                return comparison < 0;
            case "<=":
                return comparison <= 0;
            default:
                throw new RuntimeException(
                        "Failed to evaluate expression: unknown comparison operator " + expressionNode.getValue());

        }
    }

    private static int compare(Object leftValue, Object rightValue) {
        if (leftValue instanceof String leftString && rightValue instanceof String rightString) {
            return leftString.compareTo(rightString);
        } else if (isInteger(leftValue) && isInteger(rightValue)) {
            return Long.compare(((Number) leftValue).longValue(), ((Number) rightValue).longValue());
        } else if ((leftValue instanceof Float || rightValue instanceof Float)
                && leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
            // kolom FLOAT dari SM berupa float, jadi literal 0.1 dibulatkan ke float dulu biar sama
            return Float.compare(leftNumber.floatValue(), rightNumber.floatValue());
        } else if (leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        } else {
            throw new RuntimeException("Failed to evaluate expression: value not comparable");
        }
    }

    // INTEGER dari SM berupa Integer, literal dari QO berupa Long
    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    private static Object logicalAnd(ExpressionNode expressionNode, IResultRow row) {
        Object leftValue = ExpressionEvaluator.evaluate(expressionNode.getLeft(), row);
        Object rightValue = ExpressionEvaluator.evaluate(expressionNode.getRight(), row);
        if (leftValue instanceof Boolean leftBoolean && rightValue instanceof Boolean rightBoolean) {
            return leftBoolean && rightBoolean;
        } else {
            throw new RuntimeException(
                    "Failed to evaluate expression: cannot apply logical AND to non boolean value");
        }
    }
}
