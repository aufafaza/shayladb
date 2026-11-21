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
            case "getTransaction" -> null;
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

    @Test
    void createsInsertsSelectsAndDropsATableThroughTheFullPipeline() {
        useOptimizer();
        assertTrue(execute("CREATE TABLE notes (id INT PRIMARY KEY, label VARCHAR(20), score FLOAT, code CHAR(2));").success());
        assertTrue(execute("INSERT INTO notes VALUES (1, 'hello', 3.5, 'AB');").success());
        assertEquals(List.of(List.of("hello")), execute("SELECT n.label FROM notes AS n WHERE n.id = 1;").rows());
        storage.flushBuffer();
        storage = openStorage();
        useOptimizer();
        assertEquals(DataType.CHAR, storage.getTableSchema("notes").columns().get(3).type());
        assertEquals(List.of(List.of("hello")), execute("SELECT n.label FROM notes AS n;").rows());
        assertTrue(execute("DROP TABLE notes;").success());
        assertFalse(storage.listTable().contains("notes"));
        assertFalse(Files.exists(directory.resolve("notes.tbl")));
        assertFalse(openStorage().listTable().contains("notes"));
    }

    @Test
    void aliasFiltersAndSelfJoinsPreserveSeparateRowsAfterOptimization() {
        useOptimizer();
        assertTrue(execute("INSERT INTO users VALUES (1, 'Alice');").success());
        assertTrue(execute("INSERT INTO users VALUES (2, 'Bob');").success());
        assertTrue(execute("INSERT INTO users VALUES (3, 'Cara');").success());
        ExecutionResult selected = execute("SELECT a.name, b.name FROM users AS a JOIN users AS b "
            + "ON a.id < b.id WHERE a.id >= 1 ORDER BY b.id DESC, a.id ASC LIMIT 2;");
        assertTrue(selected.success(), selected.message());
        assertEquals(List.of(List.of("Alice", "Cara"), List.of("Bob", "Cara")), selected.rows());
        ExecutionResult mixedCase = execute("SELECT S.NAME FROM USERS AS s WHERE S.ID >= 2 ORDER BY S.ID DESC LIMIT 1;");
        assertTrue(mixedCase.success(), mixedCase.message());
        assertEquals(List.of(List.of("Cara")), mixedCase.rows());
        assertEquals(List.of(List.of(1, "Alice"), List.of(2, "Bob"), List.of(3, "Cara")),
            execute("SELECT * FROM users AS a NATURAL JOIN users AS b ORDER BY a.id;").rows());
    }

    @Test
    void restrictPreservesParentAndCascadeOnlyRemovesDependentConstraints() {
        useOptimizer();
        assertTrue(execute("INSERT INTO users VALUES (1, 'Alice');").success());
        assertTrue(execute("CREATE TABLE grades (id INT PRIMARY KEY, user_id INT REFERENCES users(id));").success());
        assertTrue(execute("INSERT INTO grades VALUES (42, 1);").success());
        ExecutionResult restricted = execute("DROP TABLE users RESTRICT;");
        assertFalse(restricted.success());
        assertTrue(restricted.message().contains("RESTRICT"), restricted.message());
        assertEquals(1, storage.getTableSchema("grades").foreignKeys().size());
        assertEquals(List.of(List.of("Alice")), execute("SELECT name FROM users;").rows());
        assertTrue(execute("DROP TABLE users CASCADE;").success());
        assertTrue(storage.getTableSchema("grades").foreignKeys().isEmpty());
        assertEquals(List.of(List.of(1)), execute("SELECT g.user_id FROM grades AS g;").rows());
        storage.flushBuffer();
        StorageManager reopened = openStorage();
        assertFalse(reopened.listTable().contains("users"));
        assertTrue(reopened.getTableSchema("grades").foreignKeys().isEmpty());
        assertEquals(List.of(42, 1), reopened.readBlock(new DataRetrieval("grades", List.of(), List.of())).rows().get(0).values());
    }

    @Test
    void explainShowsAliasedPlansWithoutExecutingTheQuery() {
        useOptimizer();
        ExecutionResult explained = execute("explain\n  SELECT * FROM users AS u WHERE u.id = 1;");
        assertTrue(explained.success(), explained.message());
        assertEquals(List.of(), explained.columns());
        assertTrue(explained.message().contains("Before optimization"), explained.message());
        assertTrue(explained.message().contains("After optimization"), explained.message());
        assertTrue(explained.message().contains("TABLE_SCAN [table=users, alias=u]"), explained.message());

        assertTrue(execute("EXPLAIN INSERT INTO users VALUES (1, 'Alice');").success());
        assertEquals(List.of(), execute("SELECT * FROM users;").rows(), "EXPLAIN must not insert");
    }

    private void useOptimizer() {
        processor = new QueryProcessor(new QueryOptimizer(storage, new ShaylaParser(new ShaylaLexer(""))),
            storage, storage, recovery, concurrency);
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
