package mdbms.optimizer;

import java.util.Map;

public class PlanEstimate {
    private final long estimatedRows;
    private final long estimatedBlocks;
    private final Map<String, Long> distinctValues;

    public PlanEstimate(long estimatedRows, long estimatedBlocks, Map<String, Long> distinctValues) {
        this.estimatedRows = estimatedRows;
        this.estimatedBlocks = estimatedBlocks;
        this.distinctValues = distinctValues;
    }

    public long getEstimatedRows() {
        return estimatedRows;
    }

    public long getEstimatedBlocks() {
        return estimatedBlocks;
    }

    public long getDistinctValues(String column) {
        // TODO
        return 0;
    }
}
