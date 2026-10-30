package mdbms.concurrency;

import java.util.Objects;

/** Stable identity for a row-level resource managed by a protocol. */
public final class DataItem {
    private final String tableName;
    private final String rowId;

    public DataItem(String tableName, String rowId) {
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.rowId = Objects.requireNonNull(rowId, "rowId");
    }

    public String getTableName() { return tableName; }

    public String getRowId() { return rowId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DataItem item)) return false;
        return tableName.equals(item.tableName) && rowId.equals(item.rowId);
    }

    @Override
    public int hashCode() { return Objects.hash(tableName, rowId); }

    @Override
    public String toString() { return tableName + "[" + rowId + "]"; }
}
