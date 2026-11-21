package mdbms.processor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import mdbms.api.IDataAccess;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.query.ExecutionResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.mocks.MockConcurrencyControl;
import mdbms.processor.mocks.MockDataAccess;
import mdbms.processor.mocks.MockSchemaCatalog;

public final class QueryProcessorTestDriver {
    private static final String QUERY_TABLE = "records";

    private final MockDataAccess mockDataAccess = new MockDataAccess();
    private final IDataAccess dataAccess = new TableNameMappingDataAccess(mockDataAccess);
    private final MockSchemaCatalog schemaCatalog = createSchemaCatalog();
    private final QueryProcessor queryProcessor = new QueryProcessor(
            new QueryOptimizer(schemaCatalog, new ShaylaParser()),
            dataAccess,
            schemaCatalog,
            new MockFailureRecoveryManager(),
            new MockConcurrencyControl());

    public static void main(String[] args) {
        List<ScenarioReport> reports = new ArrayList<>();
        runScenario(reports, "write operation", () -> new QueryProcessorTestDriver().testWriteOperation());
        runScenario(reports, "expression evaluation", () -> new QueryProcessorTestDriver().testExpressionEvaluation());
        runScenario(reports, "complex select", () -> new QueryProcessorTestDriver().testComplexSelect());
        runScenario(reports, "self join", () -> new QueryProcessorTestDriver().testSelfJoin());
        runScenario(reports, "delete operation", () -> new QueryProcessorTestDriver().testDeleteOperation());
        runScenario(reports, "create and drop table", () -> new QueryProcessorTestDriver().testCreateAndDropTable());
        runScenario(reports, "explain does not execute", () -> new QueryProcessorTestDriver().testExplain());
        runScenario(reports, "invalid query reports failure", () -> new QueryProcessorTestDriver().testInvalidQuery());

        printTestReport(reports);
        if (reports.stream().anyMatch(report -> !report.passed())) {
            throw new AssertionError("Query processor test driver failed");
        }
    }

    private void testWriteOperation() {
        ExecutionResult inserted = executeSuccessfully(
                "INSERT INTO records VALUES (9, 4, 'new-row', 9.5);");
        assertEquals(1, inserted.affectedRows(), "insert affected row count");

        ExecutionResult selected = executeSuccessfully(
                "SELECT id, name, score FROM records WHERE id = 9;");
        assertEquals(List.of(List.of(9, "new-row", 9.5f)), selected.rows(), "inserted values");
    }

    private void testExpressionEvaluation() {
        ExecutionResult updated = executeSuccessfully(
                "UPDATE records SET score = score + other_id * 0.25 WHERE id = 3;");
        assertEquals(1, updated.affectedRows(), "updated row count");

        ExecutionResult selected = executeSuccessfully(
                "SELECT score FROM records WHERE id = 3;");
        assertEquals(List.of(List.of(4.75f)), selected.rows(), "evaluated arithmetic result");
    }

    private void testComplexSelect() {
        ExecutionResult selected = executeSuccessfully(
                "SELECT id, name, score FROM records "
                        + "WHERE score >= 3.5 AND name <> 'row-8' "
                        + "ORDER BY score DESC, id ASC LIMIT 3;");
        assertEquals(
                List.of(
                        List.of(7, "row-7", 7.5f),
                        List.of(6, "row-6", 6.5f),
                        List.of(5, "row-5", 5.5f)),
                selected.rows(),
                "filtered, sorted, limited rows");
    }

    private void testSelfJoin() {
        ExecutionResult selected = executeSuccessfully(
                "SELECT a.name, b.name FROM records AS a "
                        + "JOIN records AS b ON a.id < b.id "
                        + "WHERE a.id = 1 ORDER BY b.id ASC LIMIT 3;");
        assertEquals(
                List.of(
                        List.of("row-1", "row-2"),
                        List.of("row-1", "row-3"),
                        List.of("row-1", "row-4")),
                selected.rows(),
                "self-join result");
    }

    private void testDeleteOperation() {
        ExecutionResult deleted = executeSuccessfully("DELETE FROM records WHERE id >= 7;");
        assertEquals(2, deleted.affectedRows(), "deleted row count");

        ExecutionResult remaining = executeSuccessfully("SELECT id FROM records ORDER BY id ASC;");
        assertEquals(
                List.of(List.of(1), List.of(2), List.of(3), List.of(4), List.of(5), List.of(6)),
                remaining.rows(),
                "rows remaining after delete");
    }

    private void testCreateAndDropTable() {
        executeSuccessfully("CREATE TABLE scratch (id INT PRIMARY KEY, label VARCHAR(20));");
        assertNotNull(schemaCatalog.getTableSchema("scratch"), "created table schema");

        executeSuccessfully("DROP TABLE scratch;");
        assertEquals(null, schemaCatalog.getTableSchema("scratch"), "dropped table schema");
    }

    private void testExplain() {
        ExecutionResult explained = executeSuccessfully(
                "EXPLAIN INSERT INTO records VALUES (9, 4, 'not-inserted', 9.5);");
        assertTrue(explained.message().contains("Before optimization"), explained.message());
        assertTrue(explained.message().contains("After optimization"), explained.message());

        ExecutionResult selected = executeSuccessfully("SELECT id FROM records WHERE id = 9;");
        assertTrue(selected.rows().isEmpty(), "EXPLAIN must not perform the insert");
    }

    private void testInvalidQuery() {
        ExecutionResult result = execute("SELECT missing FROM records;");
        assertFalse(result.success(), "invalid column should fail");
        assertTrue(result.message() != null && !result.message().isBlank(),
                "failed query should include an error message");
    }

    private ExecutionResult executeSuccessfully(String sql) {
        ExecutionResult result = execute(sql);
        assertTrue(result.success(), "Query failed: " + sql + "\n" + result.message());
        return result;
    }

    private ExecutionResult execute(String sql) {
        ExecutionResult result = queryProcessor.executeQuery(sql, 1);
        System.out.printf("SQL: %s%nSuccess: %s, affected rows: %d%nColumns: %s%nRows: %s%n\n",
                sql, result.success(), result.affectedRows(), result.columns(), result.rows());
        return result;
    }

    private static MockSchemaCatalog createSchemaCatalog() {
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        catalog.updateTableSchema(new TableSchema(
                QUERY_TABLE,
                List.of(
                        new ColumnDefinition(MockDataAccess.ID_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.SECOND_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.NAME_COLUMN, DataType.VARCHAR, 32, false),
                        new ColumnDefinition(MockDataAccess.SCORE_COLUMN, DataType.FLOAT, 0, false)),
                List.of(),
                List.of()));
        return catalog;
    }

    private static void runScenario(List<ScenarioReport> reports, String name, Runnable test) {
        try {
            test.run();
            reports.add(new ScenarioReport(name, true, "All assertions passed"));
        } catch (RuntimeException | AssertionError failure) {
            String details = failure.getMessage() != null ? failure.getMessage() : failure.toString();
            reports.add(new ScenarioReport(name, false, details));
        }
    }

    private static void printTestReport(List<ScenarioReport> reports) {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("                    QUERY PROCESSOR TEST DRIVER");
        System.out.println("--------------------------------------------------------------------------------");
        int passedCount = 0;
        for (ScenarioReport report : reports) {
            if (report.passed()) {
                passedCount++;
            }
            System.out.printf("%s %s: %s%n",
                    report.passed() ? "[PASS]" : "[FAIL]", report.name(), report.details());
        }
        System.out.printf("TOTAL: %d/%d scenarios passed%n", passedCount, reports.size());
        System.out.println("================================================================================");
    }

    private record ScenarioReport(String name, boolean passed, String details) {
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertNotNull(Object actual, String description) {
        if (actual == null) {
            throw new AssertionError(description + ": expected a value but was null");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static final class TableNameMappingDataAccess implements IDataAccess {
        private final IDataAccess delegate;

        private TableNameMappingDataAccess(IDataAccess delegate) {
            this.delegate = delegate;
        }

        @Override
        public Rows readBlock(DataRetrieval request) {
            Rows rows = delegate.readBlock(new DataRetrieval(
                    storageTableName(request.table()), request.columns(), request.conditions()));
            return new Rows(rows.rows().stream().map(this::queryTableRow).toList());
        }

        @Override
        public List<RowId> writeBlock(DataWrite request) {
            return delegate.writeBlock(new DataWrite(
                    storageTableName(request.table()), request.values(), request.conditions()))
                    .stream()
                    .map(TableNameMappingDataAccess::queryTableRowId)
                    .toList();
        }

        @Override
        public int deleteBlock(DataDeletion request) {
            return delegate.deleteBlock(new DataDeletion(
                    storageTableName(request.table()), request.conditions()));
        }

        @Override
        public Row fetchByRowId(RowId rowId) {
            return queryTableRow(delegate.fetchByRowId(storageTableRowId(rowId)));
        }

        @Override
        public RowId reserveRowId(String table, List<Object> values) {
            return queryTableRowId(delegate.reserveRowId(storageTableName(table), values));
        }

        @Override
        public void releaseRowId(RowId rowId) {
            delegate.releaseRowId(storageTableRowId(rowId));
        }

        @Override
        public void insertAt(RowId rowId, List<Object> values) {
            delegate.insertAt(storageTableRowId(rowId), values);
        }

        @Override
        public Row updateByRowId(RowId rowId, List<Object> values) {
            return queryTableRow(delegate.updateByRowId(storageTableRowId(rowId), values));
        }

        @Override
        public boolean canUpdateInPlace(RowId rowId, List<Object> values) {
            return delegate.canUpdateInPlace(storageTableRowId(rowId), values);
        }

        @Override
        public boolean updateInPlace(RowId rowId, List<Object> values) {
            return delegate.updateInPlace(storageTableRowId(rowId), values);
        }

        @Override
        public Row deleteByRowId(RowId rowId) {
            return queryTableRow(delegate.deleteByRowId(storageTableRowId(rowId)));
        }

        @Override
        public void checkPrimaryKey(String table, List<Object> values, RowId ignoreRowId) {
            delegate.checkPrimaryKey(
                    storageTableName(table),
                    values,
                    ignoreRowId == null ? null : storageTableRowId(ignoreRowId));
        }

        @Override
        public List<RowId> searchIndex(String indexName, Object key) {
            return delegate.searchIndex(indexName, key)
                    .stream()
                    .map(TableNameMappingDataAccess::queryTableRowId)
                    .toList();
        }

        @Override
        public List<RowId> searchIndexRange(
                String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive) {
            return delegate.searchIndexRange(indexName, low, high, lowInclusive, highInclusive)
                    .stream()
                    .map(TableNameMappingDataAccess::queryTableRowId)
                    .toList();
        }

        private static String storageTableName(String tableName) {
            return QUERY_TABLE.equals(tableName) ? MockDataAccess.TABLE_NAME : tableName;
        }

        private static RowId storageTableRowId(RowId rowId) {
            return QUERY_TABLE.equals(rowId.tableName())
                    ? new RowId(MockDataAccess.TABLE_NAME, rowId.blockNumber(), rowId.slot())
                    : rowId;
        }

        private static RowId queryTableRowId(RowId rowId) {
            return MockDataAccess.TABLE_NAME.equals(rowId.tableName())
                    ? new RowId(QUERY_TABLE, rowId.blockNumber(), rowId.slot())
                    : rowId;
        }

        private Row queryTableRow(Row row) {
            return row == null ? null : new Row(queryTableRowId(row.rowId()), row.values());
        }
    }

    private static final class MockFailureRecoveryManager implements IFailureRecoveryManager {
        @Override
        public RecoveryResult writeTransactionLog(int txId, LogType eventType) {
            return success();
        }

        @Override
        public RecoveryResult writeDataLog(
                int txId, LogType opType, String table, RowId rid,
                RecordImage beforeImg, RecordImage afterImg) {
            return success();
        }

        @Override
        public boolean flushLogUpTo(long lsn) {
            return true;
        }

        @Override
        public RecoveryResult rollbackTransaction(int txId) {
            return success();
        }

        @Override
        public RecoveryResult createCheckpoint() {
            return success();
        }

        @Override
        public RecoveryResult recoverOnStartup() {
            return success();
        }

        @Override
        public RecoveryResult recoverToPointInTime(Instant targetTime) {
            return success();
        }

        @Override
        public RecoveryResult recoverToTransaction(int targetTxId) {
            return success();
        }

        @Override
        public List<LogRecord> readLogs(Integer filterTxId) {
            return List.of();
        }

        private static RecoveryResult success() {
            return new RecoveryResult(true, 0, 0, "Mock recovery operation");
        }
    }
}
