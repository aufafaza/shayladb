package mdbms.app;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataRetrieval;
import mdbms.models.query.ExecutionResult;
import mdbms.models.query.ParsedQuery;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.AccessStatus;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.QueryProcessor;
import mdbms.processor.operator.CreateTableOperator;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QueryEndToEndTest {
    @TempDir Path directory;
    private final AtomicInteger transactionIds = new AtomicInteger(1);
    private StorageManager storage;
    private QueryProcessor processor;

    private final IConcurrencyControl concurrency = (IConcurrencyControl) Proxy.newProxyInstance(
        IConcurrencyControl.class.getClassLoader(), new Class<?>[] {IConcurrencyControl.class},
        (proxy, method, args) -> switch (method.getName()) {
            case "beginTransaction" -> transactionIds.getAndIncrement();
            case "requestRead", "requestWrite" -> AccessStatus.GRANTED;
            case "commitTransaction" -> null;
            case "abortTransaction" -> throw new AssertionError("Unexpected abort: " + args[1]);
            default -> throw new UnsupportedOperationException(method.getName());
        });

    private final IFailureRecoveryManager recovery = (IFailureRecoveryManager) Proxy.newProxyInstance(
        IFailureRecoveryManager.class.getClassLoader(), new Class<?>[] {IFailureRecoveryManager.class},
        (proxy, method, args) -> {
            if (method.getName().equals("flushLogUpTo")) return true;
            throw new UnsupportedOperationException("Recovery is outside this test: " + method.getName());
        });

    @BeforeEach
    void createTable() {
        storage = openStorage();
        new CreateTableOperator(storage).execute(new TableSchema("users", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 0, false),
            new ColumnDefinition("name", DataType.VARCHAR, 50, false)), List.of("id"), List.of()));
        processor = processorFor(storage);
    }

    @Test
    void insertCreatesARowInADiskBlock() throws Exception {
        System.out.println("\n=== INSERT and disk persistence ===");
        ExecutionResult result = execute("INSERT INTO users VALUES (1, 'Alice');");
        assertTrue(result.success(), result.message());
        assertEquals(1, result.affectedRows());
        storage.flushBuffer();

        assertTrue(Files.size(directory.resolve("users.tbl")) >= 4096);
        StorageManager reopened = openStorage();
        var rows = reopened.readBlock(new DataRetrieval("users", List.of(), List.of())).rows();
        assertEquals(1, rows.size());
        assertEquals(List.of(1, "Alice"), rows.get(0).values());
        System.out.println("Disk block size: " + Files.size(directory.resolve("users.tbl")) + " bytes");
        System.out.println("Rows read after reopening storage: "
            + rows.stream().map(row -> row.values()).toList());
    }

    @Test
    void selectReturnsTheCreatedRowAfterReopeningStorage() {
        System.out.println("\n=== SELECT after reopening storage ===");
        ExecutionResult inserted = execute("INSERT INTO users VALUES (1, 'Alice');");
        assertTrue(inserted.success(), inserted.message());
        storage.flushBuffer();
        storage = openStorage();
        processor = processorFor(storage);

        ExecutionResult selected = execute("SELECT id, name FROM users;");
        assertTrue(selected.success(), selected.message());
        assertEquals(List.of("id", "name"), selected.columns());
        assertEquals(List.of(List.of(1, "Alice")), selected.rows());
    }

    private StorageManager openStorage() {
        StorageManager manager = new StorageManager(directory.toString(), 8, 4);
        manager.attachRecoveryManager(recovery);
        return manager;
    }

    private QueryProcessor processorFor(StorageManager manager) {
        QueryOptimizer optimizer = new QueryOptimizer(manager, new ShaylaParser(new ShaylaLexer(""))) {
            @Override public ParsedQuery optimizeQuery(ParsedQuery query) { return query; }
        };
        return new QueryProcessor(optimizer, manager, manager, recovery, concurrency);
    }

    private ExecutionResult execute(String sql) {
        System.out.println("SQL: " + sql);
        int txId = concurrency.beginTransaction();
        ExecutionResult result = processor.executeQuery(sql, txId);
        System.out.println("Success: " + result.success() + ", affected rows: " + result.affectedRows());
        System.out.println("Columns: " + result.columns());
        System.out.println("Rows: " + result.rows());
        concurrency.commitTransaction(txId);
        return result;
    }
}
