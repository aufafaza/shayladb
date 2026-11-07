package mdbms.optimizer.cost;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class PlanEstimate {
    private final long estimatedRows;
    private final long estimatedBlocks;
    private final Map<String, Long> distinctValues;

    public PlanEstimate(long estimatedRows, long estimatedBlocks, Map<String, Long> distinctValues) {
        this.estimatedRows = Math.max(0L, estimatedRows);
        this.estimatedBlocks = Math.max(0L, estimatedBlocks);
        this.distinctValues = distinctValues != null ? Collections.unmodifiableMap(distinctValues) : Collections.emptyMap();
    }

    public long getEstimatedRows() {
        return estimatedRows;
    }

    public long getEstimatedBlocks() {
        return estimatedBlocks;
    }

    public long getDistinctValues(String column) {
        if (column == null || column.isBlank() || distinctValues.isEmpty()) {
            return 0L;
        }
        Long exact = distinctValues.get(column);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Long> entry : distinctValues.entrySet()) {
            String key = entry.getKey();
            if (key.equalsIgnoreCase(column)) {
                return entry.getValue();
            }
            // If key has table prefix (e.g. "students.id") and searched for "id"
            if (key.contains(".")) {
                String colOnly = key.substring(key.lastIndexOf('.') + 1);
                if (colOnly.equalsIgnoreCase(column)) {
                    return entry.getValue();
                }
            }
            // If searched column has prefix (e.g. "students.id") and key is "id"
            if (column.contains(".")) {
                String colOnly = column.substring(column.lastIndexOf('.') + 1);
                if (key.equalsIgnoreCase(colOnly)) {
                    return entry.getValue();
                }
            }
        }
        return 0L;
    }

    public Map<String, Long> getAllDistinctValues() {
        return distinctValues;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlanEstimate that)) return false;
        return estimatedRows == that.estimatedRows
            && estimatedBlocks == that.estimatedBlocks
            && Objects.equals(distinctValues, that.distinctValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(estimatedRows, estimatedBlocks, distinctValues);
    }

    @Override
    public String toString() {
        return "PlanEstimate{" +
            "rows=" + estimatedRows +
            ", blocks=" + estimatedBlocks +
            ", distinctValues=" + distinctValues +
            '}';
    }
}
