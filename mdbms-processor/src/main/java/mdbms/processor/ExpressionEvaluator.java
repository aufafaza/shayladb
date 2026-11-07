package mdbms.processor;

import java.util.List;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;

public class ExpressionEvaluator {
    public static Object evaluate(ExpressionTree expressionTree, IResultRow row) {
        ExpressionNode root = expressionTree.getRoot();
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
        IResultRow row
    ) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        for (ExpressionTree condition : conditions) {
            if (condition == null || condition.getRoot() == null) {
                continue;
            }

            Object result = evaluate(condition, row);

            if (!Boolean.TRUE.equals(result)) {
                return false;
            }
        }

        return true;
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
                } else if (leftValue instanceof Long leftInteger && rightValue instanceof Long rightInteger) {
                    return leftInteger + rightInteger;
                } else if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return (Double) leftValue + (Double) rightValue;
                } else {
                    throw new RuntimeException("Failed to evaluate expression: invalid plus operation");
                }

            case "-":
                if (leftValue instanceof Long leftInteger && rightValue instanceof Long rightInteger) {
                    return leftInteger - rightInteger;
                } else if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return (Double) leftValue - (Double) rightValue;
                } else {
                    throw new RuntimeException("Failed to evaluate expression: invalid minus operation");
                }

            case "*":
                if (leftValue instanceof Long leftInteger && rightValue instanceof Long rightInteger) {
                    return leftInteger * rightInteger;
                } else if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return (Double) leftValue * (Double) rightValue;
                } else {
                    throw new RuntimeException("Failed to evaluate expression: invalid multiply operation");
                }

            case "/":
                if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return (Double) leftValue / (Double) rightValue;
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
        switch (expressionNode.getValue()) {
            case "=":
                return leftValue.equals(rightValue);
            case "<>":
                return !leftValue.equals(rightValue);
            case ">":
                if (leftValue instanceof String leftString && rightValue instanceof String rightString) {
                    return leftString.compareTo(rightString) > 0;
                } else if (leftValue instanceof Long leftInteger && rightValue instanceof Long rightInteger) {
                    return Long.compare(leftInteger, rightInteger) > 0;
                } else if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return Double.compare((Double) leftValue, (Double) rightValue) > 0;
                } else {
                    throw new RuntimeException("Failed to evaluate expression: value not comparable");
                }
            case ">=":
                if (leftValue instanceof String leftString && rightValue instanceof String rightString) {
                    return leftString.compareTo(rightString) >= 0;
                } else if (leftValue instanceof Long leftInteger && rightValue instanceof Long rightInteger) {
                    return Long.compare(leftInteger, rightInteger) >= 0;
                } else if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return Double.compare((Double) leftValue, (Double) rightValue) >= 0;
                } else {
                    throw new RuntimeException("Failed to evaluate expression: value not comparable");
                }
            case "<":
                if (leftValue instanceof String leftString && rightValue instanceof String rightString) {
                    return leftString.compareTo(rightString) < 0;
                } else if (leftValue instanceof Long leftInteger && rightValue instanceof Long rightInteger) {
                    return Long.compare(leftInteger, rightInteger) < 0;
                } else if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return Double.compare((Double) leftValue, (Double) rightValue) < 0;
                } else {
                    throw new RuntimeException("Failed to evaluate expression: value not comparable");
                }
            case "<=":
                if (leftValue instanceof String leftString && rightValue instanceof String rightString) {
                    return leftString.compareTo(rightString) <= 0;
                } else if (leftValue instanceof Long leftInteger && rightValue instanceof Long rightInteger) {
                    return Long.compare(leftInteger, rightInteger) <= 0;
                } else if ((leftValue instanceof Long || leftValue instanceof Double)
                        && (rightValue instanceof Long || rightValue instanceof Double)) {
                    return Double.compare((Double) leftValue, (Double) rightValue) <= 0;
                } else {
                    throw new RuntimeException("Failed to evaluate expression: value not comparable");
                }
            default:
                throw new RuntimeException(
                        "Failed to evaluate expression: unknown comparison operator " + expressionNode.getValue());

        }
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

    public static Object evaluate(ExpressionTree expressionTree, IResultRow row1, IResultRow row2) {
        return null;
    }
}
