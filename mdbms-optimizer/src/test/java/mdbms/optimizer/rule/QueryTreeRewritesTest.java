package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.query.IndexScanSpec;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.DropBehavior;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;

class QueryTreeRewritesTest {
    @Test
    void withChildrenKeepsTheChosenIndex() {
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.INDEX_SCAN, "students",
            List.of(), List.of(), 0);
        IndexScanSpec spec = new IndexScanSpec(new IndexInfo("idx_students_id", "students", "id", IndexType.BPLUS_TREE),
            new Condition("id", ComparisonOperator.EQ, 7));
        scan.setIndexScan(spec);
        scan.setTableAlias("s");

        QueryTree copy = QueryTreeRewrites.withChildren(scan, List.of());

        assertNotSame(scan, copy);
        assertSame(spec, copy.getIndexScan());
        assertEquals("s", copy.getTableAlias());
        assertEquals("students", copy.getTableName());
    }

    @Test
    void withChildrenKeepsDdlPayloads() {
        QueryTree create = new QueryTree(QueryNodeType.CREATE_TABLE, PhysicalAlgorithm.DEFAULT, "t", List.of(), List.of(), 0);
        create.setTableDefinition(new TableSchema("t", List.of(new ColumnDefinition("id", DataType.INTEGER, 4, false)),
            List.of("id"), List.of()));
        assertEquals(create.getTableDefinition(), QueryTreeRewrites.withChildren(create, List.of()).getTableDefinition());
        QueryTree drop = new QueryTree(QueryNodeType.DROP_TABLE, PhysicalAlgorithm.DEFAULT, "t", List.of(), List.of(), 0);
        drop.setDropBehavior(DropBehavior.CASCADE);
        assertEquals(DropBehavior.CASCADE, QueryTreeRewrites.withChildren(drop, List.of()).getDropBehavior());
    }
}
