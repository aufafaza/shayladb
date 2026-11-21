package mdbms.optimizer.rule;

import java.util.List;
import mdbms.models.query.IndexScanSpec;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.DropBehavior;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryTreeRewritesTest {
    @Test void rebuildingPreservesAliasIndexLookupAndDdlPayloads() {
        QueryTree scan = node(QueryNodeType.TABLE_SCAN);
        scan.setTableAlias("s");
        scan.setIndexScan(new IndexScanSpec(new IndexInfo("idx", "t", "id", IndexType.BPLUS_TREE),
            new Condition("id", ComparisonOperator.EQ, 1)));
        QueryTree copy = QueryTreeRewrites.withChildren(scan, List.of());
        assertEquals("s", copy.getTableAlias());
        assertEquals("t", copy.getTableName());
        assertEquals(scan.getIndexScan(), copy.getIndexScan());
        QueryTree create = node(QueryNodeType.CREATE_TABLE);
        create.setTableDefinition(new TableSchema("t", List.of(new ColumnDefinition("id", DataType.INTEGER, 4, false)),
            List.of("id"), List.of()));
        assertEquals(create.getTableDefinition(), QueryTreeRewrites.withChildren(create, List.of()).getTableDefinition());
        QueryTree drop = node(QueryNodeType.DROP_TABLE);
        drop.setDropBehavior(DropBehavior.CASCADE);
        assertEquals(DropBehavior.CASCADE, QueryTreeRewrites.withChildren(drop, List.of()).getDropBehavior());
    }

    private static QueryTree node(QueryNodeType type) {
        return new QueryTree(type, PhysicalAlgorithm.DEFAULT, "t", List.of(), List.of(), 0);
    }
}
