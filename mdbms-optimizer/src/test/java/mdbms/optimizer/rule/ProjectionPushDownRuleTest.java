package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.QueryPlanPrinter;
import org.junit.jupiter.api.Test;

class ProjectionPushDownRuleTest {
    private final ProjectionPushDownRule rule = new ProjectionPushDownRule();

    private static QueryTree scan(String table) {
        return new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, table, List.of(), List.of(), 0);
    }

    private static QueryTree project(QueryTree child, ColumnReference... columns) {
        return new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(child), 0, List.of(columns), false);
    }

    private static ColumnReference col(String table, String column) {
        return new ColumnReference(table, column);
    }

    @Test
    void matchesOnlyProjectNodes() {
        assertEquals("ProjectionPushDown", rule.getRuleName());
        assertTrue(rule.matches(project(scan("students"), col("students", "id"))));
        assertFalse(rule.matches(scan("students")));
        assertFalse(rule.matches(null));
    }

    @Test
    void returnsNonProjectNodeUnchanged() {
        QueryTree scan = scan("students");
        assertSame(scan, rule.apply(scan, null));
    }

    @Test
    void collapsesChainedProjectionsIntoOutermost() {
        QueryTree chained = project(
            project(
                project(scan("students"), col("students", "id"), col("students", "name"), col("students", "gpa")),
                col("students", "id"), col("students", "name")),
            col("students", "name"));
        assertEquals(
            "PROJECT [columns=[students.name]]\n"
                + "  TABLE_SCAN [table=students]\n",
            new QueryPlanPrinter().printPlan(rule.apply(chained, null)));
    }

    @Test
    void keepsSingleProjectionAsIs() {
        QueryTree single = project(scan("students"), col("students", "name"));
        assertSame(single, rule.apply(single, null));
    }
}
