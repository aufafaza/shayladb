package mdbms.models.query;

import java.util.Objects;
import mdbms.models.data.Condition;
import mdbms.models.schema.IndexInfo;

/** Index lookup consumed by the existing physical scan operator. */
public record IndexScanSpec(IndexInfo index, Condition condition) {
    public IndexScanSpec {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(condition, "condition");
    }
}
