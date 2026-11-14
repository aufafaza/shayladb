package mdbms.models.query;

import java.util.Objects;

/** One ORDER BY column and its direction, following the model name */
public final class OrderByClause {
    private final ColumnReference field;
    private final SortDirection direction;

    public OrderByClause(ColumnReference field, SortDirection direction) {
        this.field = Objects.requireNonNull(field);
        this.direction = Objects.requireNonNull(direction);
        if (field.isWildcard()) {
            throw new IllegalArgumentException("ORDER BY requires a column, not a wildcard");
        }
    }

    /** The existing ColumnReference represents the diagram's unresolved field for column sorting. */
    public ColumnReference getField() {
        return field;
    }

    public SortDirection getDirection() {
        return direction;
    }
}
