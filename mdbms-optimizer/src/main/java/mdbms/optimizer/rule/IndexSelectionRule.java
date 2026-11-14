package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.List;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.IndexScanSpec;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.ColumnBinding;

// QO-13: pakai index buat seleksi yang langsung di atas tabel
public class IndexSelectionRule implements IOptimizationRule {
    @Override
    public String getRuleName() {
        return "IndexSelection";
    }

    @Override
    public boolean matches(QueryTree node) {
        if (node == null || node.getNodeType() != QueryNodeType.SELECT) {
            return false;
        }
        QueryTree base = baseOf(node);
        return base.getNodeType() == QueryNodeType.TABLE_SCAN && base.getIndexScan() == null;
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node) || context == null || context.getBindingContext() == null) {
            return node;
        }
        QueryTree scan = baseOf(node);
        List<ExpressionTree> conjuncts = conjunctsAbove(node);
        List<Candidate> candidates = candidatesFor(scan, conjuncts, context);
        if (candidates.isEmpty()) {
            return node;
        }
        Candidate chosen = candidates.get(0);

        QueryTree result = indexScan(scan, chosen);
        // sisa kondisi tetap jadi SELECT, urutan asli
        for (int i = conjuncts.size() - 1; i >= 0; i--) {
            if (conjuncts.get(i) != chosen.expression()) {
                result = QueryTreeRewrites.selection(conjuncts.get(i), result);
            }
        }
        return result;
    }

    record Candidate(ExpressionTree expression, IndexScanSpec spec) {
    }

    // semua kondisi yang bisa dibantu index di tabel ini
    static List<Candidate> candidatesFor(QueryTree scan, List<ExpressionTree> conjuncts, OptimizationContext context) {
        List<IndexInfo> indexes = context.getIndexes(scan.getTableName());
        List<Candidate> result = new ArrayList<>();
        if (indexes == null || indexes.isEmpty()) {
            return result;
        }
        for (ExpressionTree conjunct : conjuncts) {
            Condition condition = toCondition(conjunct, scan.getTableName(), context.getBindingContext());
            if (condition == null) {
                continue;
            }
            for (IndexInfo index : indexes) {
                if (index.columnName() != null && index.columnName().equalsIgnoreCase(condition.column())
                    && supports(index.type(), condition.operator())) {
                    result.add(new Candidate(conjunct, new IndexScanSpec(index, condition)));
                    break;
                }
            }
        }
        return result;
    }

    // B+ tree bisa = maupun range, <> ga kebantu index
    static boolean supports(IndexType type, ComparisonOperator operator) {
        if (type == null || operator == ComparisonOperator.NEQ) {
            return false;
        }
        return switch (type) {
            case BPLUS_TREE -> true;
        };
    }

    // ubah "kolom op literal" jadi Condition, null kalau bentuknya lain
    static Condition toCondition(ExpressionTree expression, String table, BindingContext bindings) {
        ExpressionNode root = expression == null ? null : expression.getRoot();
        if (root == null || root.getType() != ExpressionNode.Type.COMPARISON) {
            return null;
        }
        ExpressionNode left = root.getLeft();
        ExpressionNode right = root.getRight();
        ComparisonOperator operator = operatorOf(root.getValue());
        if (operator == null || left == null || right == null) {
            return null;
        }
        // 5 < id dibalik jadi id > 5
        if (isLiteral(left) && right.getType() == ExpressionNode.Type.COLUMN_REFERENCE) {
            ExpressionNode swap = left;
            left = right;
            right = swap;
            operator = flip(operator);
        }
        if (left.getType() != ExpressionNode.Type.COLUMN_REFERENCE || !isLiteral(right)) {
            return null;
        }
        ColumnBinding binding = bindings.getColumnBinding(new ColumnReference(left.getTableName(), left.getValue()));
        if (binding == null || table == null || !table.equalsIgnoreCase(binding.getTableName())) {
            return null;
        }
        Object value = literalValue(right);
        return value == null ? null : new Condition(binding.getColumnName(), operator, value);
    }

    private static QueryTree indexScan(QueryTree scan, Candidate chosen) {
        // kondisi ikut ditaruh biar cost model INDEX_SCAN bisa ngitung selektivitas
        QueryTree result = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.INDEX_SCAN,
            scan.getTableName(), List.of(chosen.expression()), List.of(), scan.getEstimatedCost(),
            scan.getProjectionColumns(), false);
        result.setIndexScan(chosen.spec());
        return result;
    }

    private static QueryTree baseOf(QueryTree node) {
        QueryTree base = node;
        while (base.getNodeType() == QueryNodeType.SELECT && base.getChildren().size() == 1) {
            base = base.getChildren().get(0);
        }
        return base;
    }

    private static List<ExpressionTree> conjunctsAbove(QueryTree node) {
        List<ExpressionTree> result = new ArrayList<>();
        QueryTree current = node;
        while (current.getNodeType() == QueryNodeType.SELECT && current.getChildren().size() == 1) {
            for (ExpressionTree condition : current.getConditions()) {
                result.addAll(QueryTreeRewrites.splitConjuncts(condition));
            }
            current = current.getChildren().get(0);
        }
        return result;
    }

    private static boolean isLiteral(ExpressionNode node) {
        return node.getType() == ExpressionNode.Type.INTEGER_LITERAL
            || node.getType() == ExpressionNode.Type.REAL_LITERAL
            || node.getType() == ExpressionNode.Type.STRING_LITERAL;
    }

    // tipe nilai disamain sama ExpressionEvaluator QP
    private static Object literalValue(ExpressionNode node) {
        try {
            return switch (node.getType()) {
                case INTEGER_LITERAL -> Long.parseLong(node.getValue());
                case REAL_LITERAL -> Double.parseDouble(node.getValue());
                case STRING_LITERAL -> node.getValue();
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ComparisonOperator operatorOf(String symbol) {
        if (symbol == null) {
            return null;
        }
        return switch (symbol) {
            case "=" -> ComparisonOperator.EQ;
            case "<>", "!=" -> ComparisonOperator.NEQ;
            case "<" -> ComparisonOperator.LT;
            case ">" -> ComparisonOperator.GT;
            case "<=" -> ComparisonOperator.LTE;
            case ">=" -> ComparisonOperator.GTE;
            default -> null;
        };
    }

    private static ComparisonOperator flip(ComparisonOperator operator) {
        return switch (operator) {
            case LT -> ComparisonOperator.GT;
            case GT -> ComparisonOperator.LT;
            case LTE -> ComparisonOperator.GTE;
            case GTE -> ComparisonOperator.LTE;
            default -> operator;
        };
    }
}
