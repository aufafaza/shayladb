package mdbms.optimizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryTree;

/** Read-only text rendering for query plans supplied by the driver. */
public final class QueryPlanPrinter {
    /**
     * Formats a plan using the authoritative total from ICostEstimator.calculateTotalCost(plan).
     * This method neither invokes the estimator nor interprets/sums individual node costs.
     * Lines use LF, two spaces per depth, and a final newline.
     */
    public String printPlan(QueryTree plan, int totalCost) {
        Objects.requireNonNull(plan, "plan");
        if (totalCost < 0) {
            throw new IllegalArgumentException("Total estimated cost must not be negative");
        }
        StringBuilder output = new StringBuilder("Total estimated cost: ").append(totalCost).append('\n');
        Deque<PlanEntry> pending = new ArrayDeque<>();
        pending.push(new PlanEntry(plan, 0));
        while (!pending.isEmpty()) {
            PlanEntry entry = pending.pop();
            QueryTree node = entry.node();
            if (node.getNodeType() == null) {
                throw new IllegalArgumentException("Cannot print a plan node without a node type");
            }
            output.append("  ".repeat(entry.depth())).append(node.getNodeType());
            List<String> parameters = parameters(node);
            if (!parameters.isEmpty()) {
                output.append(" [").append(String.join(", ", parameters)).append(']');
            }
            output.append('\n');
            List<QueryTree> children = node.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(new PlanEntry(children.get(i), entry.depth() + 1));
            }
        }
        return output.toString();
    }

    /** Formats both snapshots with explicit labels and their independently supplied total costs. */
    public String printPlans(QueryTree before, int beforeCost, QueryTree after, int afterCost) {
        return "Before optimization\n" + printPlan(before, beforeCost)
            + "\nAfter optimization\n" + printPlan(after, afterCost);
    }

    /** Fully parenthesized binary expressions preserve operator priority and associativity. */
    public String printExpression(ExpressionTree expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression.getRoot() == null) {
            throw new IllegalArgumentException("Cannot print an expression without a root");
        }
        return expression(expression.getRoot());
    }

    /** Prints the visual QueryTree hierarchy (including nested ExpressionTrees) to stdout. */
    public void printQueryTree(QueryTree tree) {
        Objects.requireNonNull(tree, "tree").printQueryTree();
    }

    /** Prints the visual ExpressionTree AST hierarchy to stdout. */
    public void printExpressionTree(ExpressionTree expression) {
        Objects.requireNonNull(expression, "expression").printExpressionTree();
    }

    private List<String> parameters(QueryTree node) {
        List<String> result = new ArrayList<>();
        if (node.getTableName() != null) {
            result.add("table=" + escape(node.getTableName()));
        }
        if (!node.getProjectionColumns().isEmpty()) {
            result.add("columns=[" + node.getProjectionColumns().stream().map(this::column)
                .collect(Collectors.joining(", ")) + "]");
        }
        if (node.isNaturalJoin()) {
            result.add("natural=true");
        }
        if (!node.getConditions().isEmpty()) {
            result.add("conditions=[" + node.getConditions().stream().map(this::printExpression)
                .collect(Collectors.joining(", ")) + "]");
        }
        if (!node.getOrderByClauses().isEmpty()) {
            result.add("orderBy=[" + node.getOrderByClauses().stream()
                .map(clause -> column(clause.getField()) + " " + clause.getDirection())
                .collect(Collectors.joining(", ")) + "]");
        }
        if (node.getLimit() != null) {
            result.add("limit=" + node.getLimit());
        }
        if (node.getUpdateColumn() != null && node.getUpdateExpression() != null) {
            result.add("set=" + column(node.getUpdateColumn()) + " = " + printExpression(node.getUpdateExpression()));
        }
        if (!node.getInsertValues().isEmpty()) {
            result.add("values=[" + node.getInsertValues().stream().map(this::printExpression)
                .collect(Collectors.joining(", ")) + "]");
        }
        if (node.getIndexName() != null) {
            result.add("index=" + escape(node.getIndexName()));
        }
        if (node.getIndexColumn() != null) {
            result.add("indexColumn=" + column(node.getIndexColumn()));
        }
        if (node.getAlgorithm() != null && node.getAlgorithm() != PhysicalAlgorithm.DEFAULT) {
            result.add("algorithm=" + node.getAlgorithm());
        }
        return result;
    }

    private String expression(ExpressionNode node) {
        return switch (node.getType()) {
            case INTEGER_LITERAL, REAL_LITERAL -> escape(node.getValue());
            case STRING_LITERAL -> "'" + escape(node.getValue()).replace("'", "''") + "'";
            case COLUMN_REFERENCE -> node.getTableName() == null ? escape(node.getValue())
                : escape(node.getTableName()) + "." + escape(node.getValue());
            case BINARY_OPERATION, COMPARISON, LOGICAL_AND -> "(" + expression(node.getLeft())
                + " " + node.getValue() + " " + expression(node.getRight()) + ")";
        };
    }

    private String column(ColumnReference reference) {
        return escape(reference.toString());
    }

    /** Keep each node on one line, even when a decoded string contains control characters. */
    private String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20 || ch == 0x7f) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record PlanEntry(QueryTree node, int depth) { }
}
