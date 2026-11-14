package mdbms.models.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import org.junit.jupiter.api.Test;

class IndexScanSpecTest {
    private static final IndexInfo INDEX = new IndexInfo("idx_students_id", "students", "id", IndexType.BPLUS_TREE);
    private static final Condition CONDITION = new Condition("id", ComparisonOperator.EQ, 7);

    @Test
    void keepsIndexAndCondition() {
        IndexScanSpec spec = new IndexScanSpec(INDEX, CONDITION);

        assertEquals(INDEX, spec.index());
        assertEquals(CONDITION, spec.condition());
    }

    @Test
    void rejectsMissingParts() {
        assertThrows(NullPointerException.class, () -> new IndexScanSpec(null, CONDITION));
        assertThrows(NullPointerException.class, () -> new IndexScanSpec(INDEX, null));
    }
}
