package mdbms.models.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        ExpressionTree condition = predicate();
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
        assertEquals("users", tree.getTableName());
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

    @Test
    void sixArgumentConstructorLeavesAdditionalPayloadsEmpty() {
        QueryTree tree = scan();

        assertEquals(List.of(), tree.getProjectionColumns());
        assertFalse(tree.isNaturalJoin());
    }

    @Test
    void parserPayloadsCopyColumnsAndConditions() {
        ColumnReference column = new ColumnReference("users", "id");
        List<ColumnReference> columns = new ArrayList<>(List.of(column));
        ExpressionTree predicate = predicate();
        List<ExpressionTree> conditions = new ArrayList<>(List.of(predicate));
        QueryTree tree = new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            conditions, List.of(scan()), 0, columns, false);

        columns.clear();
        conditions.clear();

        assertEquals(List.of(column), tree.getProjectionColumns());
        assertEquals(1, tree.getConditions().size());
        assertSame(predicate, tree.getConditions().get(0));
        assertThrows(UnsupportedOperationException.class, () -> tree.getProjectionColumns().clear());
        assertThrows(UnsupportedOperationException.class, () -> tree.getConditions().clear());
    }

    @Test
    void naturalJoinMarkerUsesJoinNodeKind() {
        QueryTree join = new QueryTree(QueryNodeType.JOIN, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(scan(), scan()), 0, List.of(), true);

        assertEquals(QueryNodeType.JOIN, join.getNodeType());
        assertTrue(join.isNaturalJoin());
        assertThrows(IllegalArgumentException.class, () -> new QueryTree(QueryNodeType.TABLE_SCAN,
            PhysicalAlgorithm.DEFAULT, "users", List.of(), List.of(), 0, List.of(), true));
    }

    @Test
    void compatibilityAccessorUsesTableName() {
        QueryTree tree = scan();

        assertEquals(tree.getTableName(), tree.getValue());
    }

    @Test
    void equivalentColumnReferencesResolveAsBindingMapKeys() {
        ColumnReference registered = new ColumnReference("users", "id");
        java.util.Map<ColumnReference, String> bindings = java.util.Map.of(registered, "bound-id");

        assertEquals("bound-id", bindings.get(new ColumnReference("users", "id")));
        assertEquals(registered.hashCode(), new ColumnReference("users", "id").hashCode());
        assertFalse(registered.equals(new ColumnReference("orders", "id")));
        assertFalse(registered.equals(new ColumnReference(null, "id")));
        assertFalse(registered.equals(new ColumnReference("users", "name")));
    }

    private static ExpressionTree predicate() {
        return new ExpressionTree(ExpressionNode.comparison("=", ExpressionNode.column("users", "id"),
            ExpressionNode.literal(ExpressionNode.Type.INTEGER_LITERAL, "1")));
    }

    @Test
    void orderBySetterCopiesListAndKeepsPublishedListsReadOnly() {
        QueryTree sort = new QueryTree(QueryNodeType.SORT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(scan()), 0);
        OrderByClause clause = new OrderByClause(new ColumnReference("users", "id"), SortDirection.DESC);
        List<OrderByClause> supplied = new ArrayList<>(List.of(clause));

        sort.setOrderByClauses(supplied);
        supplied.clear();

        assertEquals(List.of(clause), sort.getOrderByClauses());
        assertThrows(UnsupportedOperationException.class, () -> sort.getOrderByClauses().clear());
        List<OrderByClause> previous = sort.getOrderByClauses();
        sort.setOrderByClauses(List.of());
        assertTrue(sort.getOrderByClauses().isEmpty());
        assertEquals(List.of(clause), previous);
    }

    @Test
    void orderBySetterRejectsOtherNodeTypesAndInvalidLists() {
        for (QueryNodeType type : QueryNodeType.values()) {
            if (type != QueryNodeType.SORT) {
                QueryTree node = new QueryTree(type, PhysicalAlgorithm.DEFAULT, null,
                    List.of(), List.of(), 0);
                assertThrows(IllegalStateException.class, () -> node.setOrderByClauses(List.of()));
                assertTrue(node.getOrderByClauses().isEmpty());
            }
        }
        QueryTree sort = new QueryTree(QueryNodeType.SORT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(scan()), 0);
        OrderByClause clause = new OrderByClause(new ColumnReference(null, "id"), SortDirection.ASC);
        sort.setOrderByClauses(List.of(clause));

        assertThrows(NullPointerException.class, () -> sort.setOrderByClauses(null));
        assertThrows(NullPointerException.class,
            () -> sort.setOrderByClauses(java.util.Arrays.asList(clause, null)));
        assertEquals(List.of(clause), sort.getOrderByClauses());
    }

    @Test
    void existingConstructorsLeaveOrderByEmpty() {
        assertTrue(scan().getOrderByClauses().isEmpty());
        QueryTree project = new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(scan()), 0, List.of(new ColumnReference(null, "id")), false);
        assertTrue(project.getOrderByClauses().isEmpty());
    }

    @Test
    void orderByClauseRetainsFieldDirectionAndRejectsInvalidInputs() {
        ColumnReference field = new ColumnReference("users", "id");
        OrderByClause clause = new OrderByClause(field, SortDirection.ASC);

        assertSame(field, clause.getField());
        assertEquals(SortDirection.ASC, clause.getDirection());
        assertThrows(NullPointerException.class, () -> new OrderByClause(null, SortDirection.ASC));
        assertThrows(NullPointerException.class, () -> new OrderByClause(field, null));
        assertThrows(IllegalArgumentException.class,
            () -> new OrderByClause(new ColumnReference(null, "*"), SortDirection.ASC));
    }
}
