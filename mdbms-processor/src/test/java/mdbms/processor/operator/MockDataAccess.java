package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import mdbms.api.IDataAccess;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;

final class MockDataAccess implements IDataAccess {
    public static final String TABLE_NAME = "table";
    public static final String ID_COLUMN = "id";
    public static final String SECOND_COLUMN = "other_id";
    public static final String INDEX_NAME = "other_id_idx";
    public static final int TABLE_SIZE = 8;
    public static final int BLOCK_SIZE = 1;

    private final Map<RowId, Row> rows = new TreeMap<>((left, right) -> {
        int blockComparison = Integer.compare(left.blockNumber(), right.blockNumber());
        return blockComparison != 0 ? blockComparison : Integer.compare(left.slot(), right.slot());
    });
    private final NavigableMap<Integer, List<RowId>> indexOnOtherId = new TreeMap<>();

    public MockDataAccess() {
        for (int id = 1; id <= TABLE_SIZE; id++) {
            RowId rowId = new RowId(TABLE_NAME, (id - 1) / BLOCK_SIZE, (id - 1) % BLOCK_SIZE);
            addRow(new Row(rowId, List.of(id, ((id - 1) * 2) % TABLE_SIZE + 1)));
        }
    }

    @Override
    public Rows readBlock(DataRetrieval request) {
        requireTable(request.table());
        List<Row> result = new ArrayList<>();
        for (Row row : rows.values()) {
            if (matches(row, request.conditions())) {
                result.add(project(row, request.columns()));
            }
        }
        return new Rows(result);
    }

    @Override
    public List<RowId> writeBlock(DataWrite request) {
        requireTable(request.table());
        List<RowId> written = new ArrayList<>();
        if (request.conditions() == null || request.conditions().isEmpty()) {
            RowId rowId = reserveRowId(request.table(), valuesFrom(request.values()));
            insertAt(rowId, valuesFrom(request.values()));
            written.add(rowId);
            return written;
        }

        for (Row row : new ArrayList<>(rows.values())) {
            if (matches(row, request.conditions())) {
                List<Object> values = new ArrayList<>(row.values());
                for (var entry : request.values().entrySet()) {
                    values.set(columnIndex(entry.getKey()), entry.getValue());
                }
                updateByRowId(row.rowId(), values);
                written.add(row.rowId());
            }
        }
        return written;
    }

    @Override
    public int deleteBlock(DataDeletion request) {
        requireTable(request.table());
        List<RowId> deleted = rows.values().stream()
                .filter(row -> matches(row, request.conditions()))
                .map(Row::rowId)
                .toList();
        deleted.forEach(this::deleteByRowId);
        return deleted.size();
    }

    @Override
    public Row fetchByRowId(RowId rowId) {
        return rows.get(rowId);
    }

    @Override
    public RowId reserveRowId(String table, List<Object> values) {
        requireTable(table);
        int nextBlock = rows.keySet().stream()
                .mapToInt(RowId::blockNumber)
                .max()
                .orElse(-1) + 1;
        return new RowId(table, nextBlock, 0);
    }

    @Override
    public void releaseRowId(RowId rowId) {
        // Reservations are not persisted by this in-memory mock.
    }

    @Override
    public void insertAt(RowId rowId, List<Object> values) {
        if (!TABLE_NAME.equals(rowId.tableName())) {
            throw new IllegalArgumentException("Unknown table: " + rowId.tableName());
        }
        if (rows.containsKey(rowId)) {
            throw new IllegalArgumentException("Row already exists: " + rowId);
        }
        addRow(new Row(rowId, new ArrayList<>(values)));
    }

    @Override
    public Row updateByRowId(RowId rowId, List<Object> values) {
        Row oldRow = rows.get(rowId);
        if (oldRow == null) {
            return null;
        }
        removeFromIndex(oldRow);
        Row updated = new Row(rowId, new ArrayList<>(values));
        addRow(updated);
        return updated;
    }

    @Override
    public Row deleteByRowId(RowId rowId) {
        Row deleted = rows.remove(rowId);
        if (deleted != null) {
            removeFromIndex(deleted);
        }
        return deleted;
    }

    @Override
    public List<RowId> searchIndex(String indexName, Object key) {
        requireIndex(indexName);
        return List.copyOf(indexOnOtherId.getOrDefault((Integer) key, List.of()));
    }

    @Override
    public List<RowId> searchIndexRange(String indexName, Object low, Object high,
            boolean lowInclusive, boolean highInclusive) {
        requireIndex(indexName);
        NavigableMap<Integer, List<RowId>> range = indexOnOtherId;
        if (low != null && high != null) {
            range = range.subMap((Integer) low, lowInclusive, (Integer) high, highInclusive);
        } else if (low != null) {
            range = range.tailMap((Integer) low, lowInclusive);
        } else if (high != null) {
            range = range.headMap((Integer) high, highInclusive);
        }
        return range.values().stream().flatMap(List::stream).toList();
    }

    private void addRow(Row row) {
        rows.put(row.rowId(), row);
        indexOnOtherId.computeIfAbsent((Integer) row.values().get(1), ignored -> new ArrayList<>())
                .add(row.rowId());
    }

    private void removeFromIndex(Row row) {
        Integer key = (Integer) row.values().get(1);
        List<RowId> rowIds = indexOnOtherId.get(key);
        rowIds.remove(row.rowId());
        if (rowIds.isEmpty()) {
            indexOnOtherId.remove(key);
        }
    }

    private boolean matches(Row row, List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        return conditions.stream().allMatch(condition -> {
            int comparison = Integer.compare(
                    (Integer) row.values().get(columnIndex(condition.column())),
                    (Integer) condition.value());
            return switch (condition.operator()) {
                case EQ -> comparison == 0;
                case NEQ -> comparison != 0;
                case LT -> comparison < 0;
                case GT -> comparison > 0;
                case LTE -> comparison <= 0;
                case GTE -> comparison >= 0;
            };
        });
    }

    private Row project(Row row, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return row;
        }
        return new Row(row.rowId(), columns.stream()
                .map(column -> row.values().get(columnIndex(column)))
                .toList());
    }

    private List<Object> valuesFrom(Map<String, Object> values) {
        return List.of(values.get(ID_COLUMN), values.get(SECOND_COLUMN));
    }

    private int columnIndex(String column) {
        return switch (column) {
            case ID_COLUMN -> 0;
            case SECOND_COLUMN -> 1;
            default -> throw new IllegalArgumentException("Unknown column: " + column);
        };
    }

    private void requireTable(String table) {
        if (!TABLE_NAME.equals(table)) {
            throw new IllegalArgumentException("Unknown table: " + table);
        }
    }

    private void requireIndex(String indexName) {
        if (!INDEX_NAME.equals(indexName)) {
            throw new IllegalArgumentException("Unknown index: " + indexName);
        }
    }
}
