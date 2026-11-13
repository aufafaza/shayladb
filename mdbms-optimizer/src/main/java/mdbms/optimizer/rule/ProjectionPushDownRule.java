package mdbms.optimizer.rule;

import java.util.List;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;

// QO-10: optimasi proyeksi
public class ProjectionPushDownRule implements IOptimizationRule {
    @Override
    public String getRuleName() {
        return "ProjectionPushDown";
    }

    @Override
    public boolean matches(QueryTree node) {
        return node != null && node.getNodeType() == QueryNodeType.PROJECT;
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node) || node.getChildren().size() != 1) {
            return node;
        }
        return collapseChain(node);
    }

    // PROJECT berantai cukup diwakili yang paling luar
    private static QueryTree collapseChain(QueryTree project) {
        QueryTree child = project.getChildren().get(0);
        while (child.getNodeType() == QueryNodeType.PROJECT && child.getChildren().size() == 1) {
            child = child.getChildren().get(0);
        }
        return child == project.getChildren().get(0)
            ? project
            : QueryTreeRewrites.withChildren(project, List.of(child));
    }
}
