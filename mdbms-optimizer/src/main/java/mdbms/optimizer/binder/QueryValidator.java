package mdbms.optimizer.binder;

import java.util.List;
import java.util.Objects;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QuerySemanticException;

public class QueryValidator implements IQueryValidator {
    private final QueryBinder binder;
    private final QueryErrorCollector errorCollector;

    public QueryValidator() {
        this((QueryBinder) null, null);
    }

    public QueryValidator(QueryBinder binder) {
        this(binder, null);
    }

    public QueryValidator(QueryBinder binder, QueryErrorCollector errorCollector) {
        this.binder = binder;
        this.errorCollector = errorCollector;
    }

    public QueryValidator(ISchemaCatalog schemaCatalog) {
        this(schemaCatalog, null);
    }

    public QueryValidator(ISchemaCatalog schemaCatalog, QueryErrorCollector errorCollector) {
        this.binder = new QueryBinder(schemaCatalog, errorCollector);
        this.errorCollector = errorCollector;
    }

    private QuerySemanticException semanticError(String message) {
        return semanticError(message, null);
    }

    private QuerySemanticException semanticError(String message, String problematicName) {
        QuerySemanticException ex = new QuerySemanticException(message, problematicName);
        if (errorCollector != null) {
            errorCollector.addException(ex);
        }
        return ex;
    }

    @Override
    public boolean validate(QueryTree tree) {
        if (tree == null) {
            return false;
        }
        BindingContext context = binder != null ? binder.bind(tree) : new BindingContext();
        validate(tree, context);
        return true;
    }

    public void validate(QueryTree query, BindingContext context) {
        if (query == null) {
            return;
        }
        Objects.requireNonNull(context, "context must not be null");
        validateNode(query, context);
    }

    private void validateNode(QueryTree node, BindingContext context) {
        if (node == null) {
            return;
        }

        // Validate children first (post-order traversal)
        for (QueryTree child : node.getChildren()) {
            validateNode(child, context);
        }

        // Validate any conditions on the current node
        for (ExpressionTree condition : node.getConditions()) {
            validateColumnTypes(condition, context);
        }

        switch (node.getNodeType()) {
            case INSERT -> validateInsertValues(node, context);
            case UPDATE -> validateUpdateAssignments(node, context);
            default -> {
                // Other nodes are validated via children and conditions
            }
        }
    }

    private void validateColumnTypes(ExpressionTree expression, BindingContext context) {
        if (expression == null || expression.getRoot() == null) {
            return;
        }
        validateExpressionTypes(expression, context);
    }

    private DataType validateExpressionTypes(ExpressionTree expression, BindingContext context) {
        if (expression == null || expression.getRoot() == null) {
            return null;
        }
        return validateExpressionNode(expression.getRoot(), context);
    }

    private DataType validateExpressionNode(ExpressionNode node, BindingContext context) {
        if (node == null) {
            return null;
        }

        switch (node.getType()) {
            case INTEGER_LITERAL -> {
                return DataType.INTEGER;
            }
            case REAL_LITERAL -> {
                return DataType.FLOAT;
            }
            case STRING_LITERAL -> {
                return DataType.VARCHAR;
            }
            case COLUMN_REFERENCE -> {
                ColumnReference ref = new ColumnReference(node.getTableName(), node.getValue());
                ColumnBinding cb = context.getColumnBinding(ref);
                if (cb == null && node.getTableName() != null) {
                    cb = context.getColumnBinding(new ColumnReference(null, node.getValue()));
                }
                if (cb == null) {
                    throw semanticError(
                        "Column '" + node.getValue() + "' is not bound in context",
                        node.getValue()
                    );
                }
                return cb.getDataType();
            }
            case BINARY_OPERATION -> {
                DataType left = validateExpressionNode(node.getLeft(), context);
                DataType right = validateExpressionNode(node.getRight(), context);
                if (left == null || right == null) {
                    throw semanticError("Incomplete operands for arithmetic operator '" + node.getValue() + "'", node.getValue());
                }
                if (!isNumeric(left) || !isNumeric(right)) {
                    throw semanticError(
                        "Arithmetic operator '" + node.getValue() + "' cannot be applied to non-numeric types: " + left + " and " + right,
                        node.getValue()
                    );
                }
                // Numeric promotion: FLOAT has higher precedence than INTEGER
                return (left == DataType.FLOAT || right == DataType.FLOAT) ? DataType.FLOAT : DataType.INTEGER;
            }
            case COMPARISON -> {
                DataType left = validateExpressionNode(node.getLeft(), context);
                DataType right = validateExpressionNode(node.getRight(), context);
                if (left == null || right == null) {
                    throw semanticError("Incomplete operands for comparison operator '" + node.getValue() + "'", node.getValue());
                }
                if (isNumeric(left) && isNumeric(right)) {
                    return DataType.INTEGER; // boolean representation
                }
                if (isText(left) && isText(right)) {
                    return DataType.INTEGER; // boolean representation
                }
                throw semanticError(
                    "Cannot compare incompatible types: " + left + " and " + right + " with operator '" + node.getValue() + "'",
                    node.getValue()
                );
            }
            case LOGICAL_AND -> {
                validateExpressionNode(node.getLeft(), context);
                validateExpressionNode(node.getRight(), context);
                return DataType.INTEGER; // boolean representation
            }
            default -> {
                return null;
            }
        }
    }

    private void validateInsertValues(QueryTree node, BindingContext context) {
        String tableName = node.getTableName();
        TableBinding tb = context.getTableBinding(tableName);
        if (tb == null) {
            throw semanticError("Target table '" + tableName + "' not found for INSERT", tableName);
        }

        List<ColumnDefinition> columns = tb.getSchema().columns();
        List<ExpressionTree> values = node.getInsertValues();

        if (values.size() != columns.size()) {
            throw semanticError(
                "INSERT value count (" + values.size() + ") does not match table column count (" + columns.size() + ") for table '" + tableName + "'",
                tableName
            );
        }

        for (int i = 0; i < values.size(); i++) {
            DataType valueType = validateExpressionTypes(values.get(i), context);
            ColumnDefinition column = columns.get(i);
            DataType columnType = column.type();

            if (!isAssignmentCompatible(columnType, valueType)) {
                throw semanticError(
                    "Type mismatch on column '" + column.name() + "' for table '" + tableName + "': expected " + columnType + " but found " + valueType,
                    column.name()
                );
            }
        }
    }

    private void validateUpdateAssignments(QueryTree node, BindingContext context) {
        ColumnReference updateCol = node.getUpdateColumn();
        if (updateCol == null) {
            throw semanticError("UPDATE statement must specify a target column");
        }

        ColumnBinding cb = context.getColumnBinding(updateCol);
        if (cb == null) {
            throw semanticError(
                "Target column '" + updateCol.getColumnName() + "' not found in table '" + node.getTableName() + "'",
                updateCol.getColumnName()
            );
        }

        if (node.getUpdateExpression() != null) {
            DataType exprType = validateExpressionTypes(node.getUpdateExpression(), context);
            DataType targetType = cb.getDataType();

            if (!isAssignmentCompatible(targetType, exprType)) {
                throw semanticError(
                    "Cannot assign " + exprType + " to column '" + cb.getColumnName() + "' of type " + targetType + " in UPDATE statement",
                    cb.getColumnName()
                );
            }
        }
    }

    private boolean isNumeric(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }

    private boolean isText(DataType type) {
        return type == DataType.CHAR || type == DataType.VARCHAR;
    }

    private boolean isAssignmentCompatible(DataType target, DataType source) {
        if (target == null || source == null) {
            return false;
        }
        if (target == source) {
            return true;
        }
        if (isText(target) && isText(source)) {
            return true;
        }
        if (isNumeric(target) && isNumeric(source)) {
            // Integer can be widened to Float, but Float cannot be assigned to Integer without precision loss
            return target != DataType.INTEGER || source != DataType.FLOAT;
        }
        return false;
    }
}
