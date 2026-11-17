package mdbms.optimizer.rule;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.query.IndexScanSpec;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import org.junit.jupiter.api.Test;

class QueryTreeRewritesTest {
    @Test
    void withChildrenKeepsTheChosenIndex() {
        QueryTree scan = new QueryTree(QueryNodeType.TABLE_SCAN, PhysicalAlgorithm.INDEX_SCAN, "students",
            List.of(), List.of(), 0);
        IndexScanSpec spec = new IndexScanSpec(new IndexInfo("idx_students_id", "students", "id", IndexType.BPLUS_TREE),
            new Condition("id", ComparisonOperator.EQ, 7));
        scan.setIndexScan(spec);

        QueryTree copy = QueryTreeRewrites.withChildren(scan, List.of());

        assertNotSame(scan, copy);
        assertSame(spec, copy.getIndexScan());
    }
}
