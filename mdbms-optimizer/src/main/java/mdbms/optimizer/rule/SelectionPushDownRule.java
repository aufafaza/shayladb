package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.List;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;

// QO-09: optimasi seleksi
public class SelectionPushDownRule implements IOptimizationRule {
    @Override
    public String getRuleName() {
        return "SelectionPushDown";
    }

    @Override
    public boolean matches(QueryTree node) {
        return node != null && node.getNodeType() == QueryNodeType.SELECT;
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node)) {
            return node;
        }
        // kumpulin kondisi dari SELECT yang bertumpuk
        List<ExpressionTree> conjuncts = new ArrayList<>();
        QueryTree source = node;
        while (source.getNodeType() == QueryNodeType.SELECT && source.getChildren().size() == 1) {
            for (ExpressionTree condition : source.getConditions()) {
                conjuncts.addAll(QueryTreeRewrites.splitConjuncts(condition));
            }
            source = source.getChildren().get(0);
        }

        // susun ulang jadi rantai, kondisi pertama di atas
        QueryTree result = source;
        for (int i = conjuncts.size() - 1; i >= 0; i--) {
            result = QueryTreeRewrites.selection(conjuncts.get(i), result);
        }
        return result;
    }
}
