package mdbms.models.query;

import java.util.Objects;
import mdbms.models.data.Condition;
import mdbms.models.schema.IndexInfo;

// index yang dipake TABLE_SCAN beserta kondisi pencariannya
public record IndexScanSpec(IndexInfo index, Condition condition) {
    public IndexScanSpec {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(condition, "condition");
    }
}
