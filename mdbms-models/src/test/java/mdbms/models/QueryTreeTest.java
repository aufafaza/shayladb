package mdbms.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTreeTest {

    private static QueryTree scan() {
        return new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.DEFAULT, "users", List.of(), List.of(), 0);
    }

    @Test
    void constructorSetsFields() {
        Condition condition = new Condition("id", ComparisonOperator.EQ, 1);
        QueryTree child = scan();

        QueryTree tree = new QueryTree(
            QueryNodeType.SELECT,
            PhysicalAlgorithm.LINEAR_SCAN,
            "users",
            List.of(condition),
            List.of(child),
            5
        );

        assertEquals(QueryNodeType.SELECT, tree.getNodeType());
        assertEquals(PhysicalAlgorithm.LINEAR_SCAN, tree.getAlgorithm());
        assertEquals("users", tree.getValue());
        assertEquals(List.of(condition), tree.getConditions());
        assertSame(child, tree.getChildren().get(0));
        assertEquals(5, tree.getEstimatedCost());
    }

    @Test
    void setAlgorithmChangesAlgorithm() {
        QueryTree tree = scan();

        tree.setAlgorithm(PhysicalAlgorithm.INDEX_SCAN);

        assertEquals(PhysicalAlgorithm.INDEX_SCAN, tree.getAlgorithm());
    }

    @Test
    void setEstimatedCostChangesCost() {
        QueryTree tree = scan();

        tree.setEstimatedCost(42);

        assertEquals(42, tree.getEstimatedCost());
    }

    @Test
    void childrenAreCopiedAndReadOnly() {
        List<QueryTree> children = new ArrayList<>();
        QueryTree tree = new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, "id", List.of(), children, 0);

        children.add(scan());

        assertEquals(0, tree.getChildren().size());
        assertThrows(UnsupportedOperationException.class, () -> tree.getChildren().add(scan()));
    }

    @Test
    void parsedQueryExposesTree() {
        QueryTree tree = scan();

        ParsedQuery parsed = new ParsedQuery("SELECT * FROM users", tree, List.of("users"));

        assertSame(tree, parsed.getQueryTree());
    }
}
