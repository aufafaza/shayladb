package mdbms.models.query;

import java.util.ArrayList;
import java.util.List;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.DropBehavior;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryTreeDdlTest {
    @Test void tableDefinitionCopiesListsAndRequiresMatchingNode() {
        List<ColumnDefinition> columns = new ArrayList<>(List.of(new ColumnDefinition("id", DataType.INTEGER, 4, false)));
        QueryTree create = node(QueryNodeType.CREATE_TABLE);
        create.setTableDefinition(new TableSchema("t", columns, List.of("id"), List.of()));
        columns.clear();
        assertEquals(1, create.getTableDefinition().columns().size());
        assertThrows(UnsupportedOperationException.class, () -> create.getTableDefinition().columns().clear());
        assertThrows(IllegalArgumentException.class, () -> create.setTableDefinition(
            new TableSchema("other", List.of(), List.of(), List.of())));
        assertThrows(IllegalStateException.class, () -> node(QueryNodeType.TABLE_SCAN).setTableDefinition(create.getTableDefinition()));
    }

    @Test void aliasAndDropBehaviorBelongToTheirOwnNodeTypes() {
        QueryTree scan = node(QueryNodeType.TABLE_SCAN);
        scan.setTableAlias("s");
        assertEquals("t", scan.getTableName());
        assertEquals("s", scan.getBindingName());
        assertThrows(IllegalArgumentException.class, () -> scan.setTableAlias(" "));
        QueryTree drop = node(QueryNodeType.DROP_TABLE);
        assertEquals(DropBehavior.RESTRICT, drop.getDropBehavior());
        drop.setDropBehavior(DropBehavior.CASCADE);
        assertTrue(drop.formatTree().contains("CASCADE"));
        assertThrows(IllegalStateException.class, () -> drop.setTableAlias("s"));
        assertThrows(IllegalStateException.class, () -> scan.setDropBehavior(DropBehavior.CASCADE));
    }

    private static QueryTree node(QueryNodeType type) {
        return new QueryTree(type, PhysicalAlgorithm.DEFAULT, "t", List.of(), List.of(), 0);
    }
}
