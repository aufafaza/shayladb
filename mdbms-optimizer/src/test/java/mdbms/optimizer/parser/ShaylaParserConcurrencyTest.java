package mdbms.optimizer.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ParsedQuery;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.Main;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.QueryPlanPrinter;
import mdbms.optimizer.exception.ErrorCategory;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QueryLexicalException;
import mdbms.optimizer.exception.QuerySemanticException;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.ShaylaLexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class ShaylaParserConcurrencyTest {
    private static final String ALPHA = "SELECT alpha.name FROM alpha WHERE alpha.id = 11;";
    private static final String BETA = "SELECT beta.code FROM beta WHERE beta.id = 22;";

    @Test
    void overlappingCallsKeepTheirOwnTablesColumnsLiteralsAndCollectors() throws Exception {
        try (Interleaving calls = new Interleaving(ALPHA)) {
            var results = calls.run(() -> calls.parser.parseToTree(ALPHA), () -> calls.parser.parseToTree(BETA));

            assertSelect(results.first(), "alpha", "name", "11");
            assertSelect(results.second(), "beta", "code", "22");
            assertNotSame(results.first(), results.second());
            assertNotSame(calls.parser.collectors.get(ALPHA), calls.parser.collectors.get(BETA));
            assertTrue(calls.parser.collectors.get(ALPHA).getAllExceptions().isEmpty());
            assertTrue(calls.parser.collectors.get(BETA).getAllExceptions().isEmpty());
        }
    }

    @Test
    void invalidRequestDoesNotContaminateAnOverlappingOrLaterValidRequest() throws Exception {
        String invalid = "SELECT @ FROM beta";
        try (Interleaving calls = new Interleaving(ALPHA)) {
            var results = calls.run(() -> calls.parser.parseToTree(ALPHA),
                    () -> assertThrows(QueryLexicalException.class, () -> calls.parser.parseToTree(invalid)));

            assertSelect(results.first(), "alpha", "name", "11");
            assertEquals(invalid, results.second().getSqlSource());
            assertEquals("@", results.second().getLexeme());
            assertTrue(calls.parser.collectors.get(ALPHA).getAllExceptions().isEmpty());
            assertEquals(List.of(results.second()), calls.parser.collectors.get(invalid).getAllExceptions());

            assertSelect(calls.parser.parseToTree(BETA), "beta", "code", "22");
            assertTrue(calls.parser.collectors.get(BETA).getAllExceptions().isEmpty());
        }
    }

    @Test
    void overlappingFailuresKeepTheirOwnSourcesPositionsCategoriesAndDiagnosticCounts() throws Exception {
        String lexical = "SELECT @, ? FROM alpha";
        String syntax = "\nSELECT FROM beta";
        try (Interleaving calls = new Interleaving(lexical)) {
            var results = calls.run(
                    () -> assertThrows(QueryLexicalException.class, () -> calls.parser.parseToTree(lexical)),
                    () -> assertThrows(QuerySyntaxException.class, () -> calls.parser.parseToTree(syntax)));

            assertEquals(lexical, results.first().getSqlSource());
            assertEquals(ErrorCategory.LEXICAL_ERROR, results.first().getCategory());
            assertEquals("@", results.first().getLexeme());
            assertEquals(1, results.first().getLine());
            assertEquals(8, results.first().getColumn());
            var lexicalErrors = calls.parser.collectors.get(lexical).getAllExceptions();
            assertEquals(List.of("@", "?"), lexicalErrors.stream().map(error -> error.getLexeme()).toList());
            assertSame(results.first(), lexicalErrors.get(0));
            assertTrue(lexicalErrors.stream().allMatch(error -> lexical.equals(error.getSqlSource())));

            assertEquals(syntax, results.second().getSqlSource());
            assertEquals(ErrorCategory.SYNTAX_ERROR, results.second().getCategory());
            assertEquals("FROM", results.second().getLexeme());
            assertEquals(2, results.second().getLine());
            assertEquals(8, results.second().getColumn());
            assertEquals(List.of(results.second()), calls.parser.collectors.get(syntax).getAllExceptions());
            assertNotSame(calls.parser.collectors.get(lexical), calls.parser.collectors.get(syntax));
        }
    }

    @Test
    void sharedOptimizerPreservesEachRequestsBindingsAndOptimizedPlan() throws Exception {
        try (Interleaving calls = new Interleaving(ALPHA)) {
            QueryOptimizer optimizer = new QueryOptimizer(catalog(), calls.parser);
            var results = calls.run(() -> optimizer.optimizeQuery(optimizer.parseQuery(ALPHA)),
                    () -> optimizer.optimizeQuery(optimizer.parseQuery(BETA)));

            assertPlan(results.first(), ALPHA, "alpha", "name", "11");
            assertPlan(results.second(), BETA, "beta", "code", "22");
            assertNotSame(results.first().queryTree(), results.second().queryTree());
            assertTrue(optimizer.getCost(results.first()) >= 0);
            assertTrue(optimizer.getCost(results.second()) >= 0);
        }
    }

    @Test
    void semanticFailureInSharedOptimizerDoesNotPoisonOtherRequests() throws Exception {
        String invalid = "SELECT missing FROM beta;";
        try (Interleaving calls = new Interleaving(ALPHA)) {
            QueryOptimizer optimizer = new QueryOptimizer(catalog(), calls.parser);
            var results = calls.run(() -> optimizer.optimizeQuery(optimizer.parseQuery(ALPHA)),
                    () -> assertThrows(QuerySemanticException.class, () -> optimizer.parseQuery(invalid)));

            assertPlan(results.first(), ALPHA, "alpha", "name", "11");
            assertTrue(results.second().getMessage().contains("missing"));
            assertPlan(optimizer.optimizeQuery(optimizer.parseQuery(BETA)), BETA, "beta", "code", "22");
        }
    }

    private static void assertSelect(QueryTree tree, String table, String column, String literal) {
        assertEquals(QueryNodeType.PROJECT, tree.getNodeType());
        assertEquals(List.of(new ColumnReference(table, column)), tree.getProjectionColumns());
        QueryTree filter = tree.getChildren().get(0);
        assertEquals(QueryNodeType.SELECT, filter.getNodeType());
        assertEquals(literal, filter.getConditions().get(0).getRoot().getRight().getValue());
        assertEquals(table, filter.getChildren().get(0).getTableName());
    }

    private static void assertPlan(ParsedQuery query, String sql, String table, String column, String literal) {
        assertEquals(sql, query.rawQuery());
        assertEquals(List.of(table), query.targetTables());
        assertEquals(List.of(new ColumnReference(table, column)), query.queryTree().getProjectionColumns());
        List<String> scans = new ArrayList<>();
        collectScans(query.queryTree(), scans);
        assertEquals(List.of(table), scans);
        assertTrue(new QueryPlanPrinter().printPlan(query.queryTree()).contains("= " + literal + ")"));
    }

    private static void collectScans(QueryTree tree, List<String> scans) {
        if (tree.getNodeType() == QueryNodeType.TABLE_SCAN) scans.add(tree.getTableName());
        for (QueryTree child : tree.getChildren()) collectScans(child, scans);
    }

    private static Main.InMemorySchemaCatalog catalog() {
        Main.InMemorySchemaCatalog catalog = new Main.InMemorySchemaCatalog();
        for (String table : List.of("alpha", "beta")) {
            catalog.addTable(new TableSchema(table, List.of(
                    new ColumnDefinition("id", DataType.INTEGER, 0, false),
                    new ColumnDefinition(table.equals("alpha") ? "name" : "code", DataType.VARCHAR, 50, false)),
                    List.of("id"), List.of()));
        }
        return catalog;
    }

    private record Results<A, B>(A first, B second) {}

    private static final class Interleaving implements AutoCloseable {
        final CountDownLatch firstInsideParser = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final GatedParser parser;

        Interleaving(String firstSql) {
            parser = new GatedParser(firstSql, firstInsideParser, releaseFirst);
        }

        <A, B> Results<A, B> run(Callable<A> first, Callable<B> second) throws Exception {
            var firstResult = workers.submit(first);
            assertTrue(firstInsideParser.await(5, TimeUnit.SECONDS), "first request must pause inside parsing");
            B secondResult;
            try {
                // The second request must finish while the first remains inside parseToTree.
                // This also detects a lock that unintentionally serializes all parsing.
                secondResult = workers.submit(second).get(5, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
            return new Results<>(firstResult.get(5, TimeUnit.SECONDS), secondResult);
        }

        @Override public void close() throws InterruptedException {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "parser workers must terminate");
        }
    }

    private static final class GatedParser extends ShaylaParser {
        final Map<String, QueryErrorCollector> collectors = new ConcurrentHashMap<>();
        final String pausedSql;
        final CountDownLatch firstInsideParser;
        final CountDownLatch releaseFirst;

        GatedParser(String pausedSql, CountDownLatch firstInsideParser, CountDownLatch releaseFirst) {
            this.pausedSql = pausedSql;
            this.firstInsideParser = firstInsideParser;
            this.releaseFirst = releaseFirst;
        }

        @Override protected ShaylaLexer createLexer(String sql, QueryErrorCollector collector) {
            collectors.put(sql, collector);
            return new ShaylaLexer(sql, collector) {
                @Override public String getInput() {
                    if (pausedSql.equals(sql)) {
                        firstInsideParser.countDown();
                        try {
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS), "first request must be released");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("parser overlap test interrupted", interrupted);
                        }
                    }
                    return super.getInput();
                }
            };
        }
    }
}
