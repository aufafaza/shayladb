package mdbms.processor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.RowId;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.mocks.MockConcurrencyControl;
import mdbms.processor.mocks.MockDataAccess;
import mdbms.processor.mocks.MockSchemaCatalog;

/**
 * QueryProcessorTestDriver
 * 
 * Driver ini berasumsi QueryOptimizer udah bener.
 * Sisanya pake mock yang dibuat.
 */
final class QueryProcessorTestDriver {
    private final List<ScenarioReport> reports = new ArrayList<>();
    private final MockDataAccess dataAccess = new MockDataAccess();
    private final MockSchemaCatalog schemaCatalog = createSchemaCatalog();
    private final MockFailureRecoveryManager failureRecovery = new MockFailureRecoveryManager();
    private final MockConcurrencyControl concurrencyControl = new MockConcurrencyControl();
    private final QueryProcessor queryProcessor;

    private QueryProcessorTestDriver() {
        queryProcessor = new QueryProcessor(
                new QueryOptimizer(schemaCatalog, new ShaylaParser()),
                dataAccess,
                schemaCatalog,
                failureRecovery,
                concurrencyControl);
    }

    public static void main(String[] args) {
        new QueryProcessorTestDriver();
    }

    public void printTestReport() {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("                    RINGKASAN HASIL PENGUJIAN QUERY PROCESSOR                    ");
        System.out.println("--------------------------------------------------------------------------------");
        int passedCount = 0;
        for (ScenarioReport report : reports) {
            if (report.passed)
                passedCount++;
            System.out.println("Skenario : " + report.name);
            System.out.println("Expected : " + report.expected);
            System.out.println("Actual   : " + report.actual);
            System.out.println("Status   : " + (report.passed ? "[PASS]" : "[FAIL]"));
            System.out.println("--------------------------------------------------------------------------------");
        }
        System.out.printf("TOTAL: %d/%d Skenario Lulus%n", passedCount, reports.size());
        System.out.println("================================================================================");
    }

    private record ScenarioReport(String name, String expected, String actual, boolean passed) {
    }

    private static MockSchemaCatalog createSchemaCatalog() {
        MockSchemaCatalog catalog = new MockSchemaCatalog();
        catalog.updateTableSchema(new TableSchema(
                MockDataAccess.TABLE_NAME,
                List.of(
                        new ColumnDefinition(MockDataAccess.ID_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.SECOND_COLUMN, DataType.INTEGER, 0, false),
                        new ColumnDefinition(MockDataAccess.NAME_COLUMN, DataType.VARCHAR, 32, false),
                        new ColumnDefinition(MockDataAccess.SCORE_COLUMN, DataType.FLOAT, 0, false)),
                List.of(),
                List.of()));
        return catalog;
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

    private static String ResultSchemaToString(ResultTableSchema schema) {
        return String.join(",",
                schema.getColumns().stream().map((c) -> c.toString()).toList());
    }

    private static String ResultRowToString(IResultRow resultRow) {
        return String.join(",",
                resultRow.getSchema().getColumns().stream()
                        .map((ColumnDefinition c) -> String.valueOf(
                                resultRow.get(resultRow.getSchema().getColumnTableOrigin(c), c.name())))
                        .toList());
    }
}
