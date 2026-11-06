package mdbms.optimizer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.query.SortDirection;

class ShaylaParserTest {
    private final ShaylaParser parser = new ShaylaParser(new ShaylaLexer("old input"));

    @Test
    void parsesFullSelectSourceIntoTheExistingUnboundTree() {
        QueryTree project = parser.parseToTree("SELECT siswa.nama, siswa.umur FROM siswa "
            + "WHERE umur >= 18 ORDER BY umur DESC, nama;");
        QueryTree sort = project.getChildren().get(0);
        QueryTree filter = sort.getChildren().get(0);

        assertEquals(QueryNodeType.PROJECT, project.getNodeType());
        assertEquals(List.of(new ColumnReference("siswa", "nama"), new ColumnReference("siswa", "umur")),
            project.getProjectionColumns());
        assertEquals(QueryNodeType.SORT, sort.getNodeType());
        assertEquals(SortDirection.DESC, sort.getOrderByClauses().get(0).getDirection());
        assertEquals(SortDirection.ASC, sort.getOrderByClauses().get(1).getDirection());
        assertEquals(QueryNodeType.SELECT, filter.getNodeType());
        assertEquals(">=", filter.getConditions().get(0).getRoot().getValue());
        assertEquals("siswa", filter.getChildren().get(0).getTableName());
    }

    @Test
    void keepsCartesianConditionalAndNaturalJoinPayloadsForLaterBinding() {
        QueryTree project = parser.parseToTree("SELECT * FROM a, b JOIN c ON b.id = c.bid NATURAL JOIN d");
        QueryTree natural = project.getChildren().get(0);
        QueryTree conditional = natural.getChildren().get(0);
        QueryTree cartesian = conditional.getChildren().get(0);

        assertTrue(project.getProjectionColumns().get(0).isWildcard());
        assertTrue(natural.isNaturalJoin());
        assertTrue(natural.getConditions().isEmpty());
        assertEquals("d", natural.getChildren().get(1).getTableName());
        assertFalse(conditional.isNaturalJoin());
        assertEquals("=", conditional.getConditions().get(0).getRoot().getValue());
        assertEquals(QueryNodeType.CARTESIAN, cartesian.getNodeType());
        assertEquals(List.of("a", "b"), cartesian.getChildren().stream().map(QueryTree::getTableName).toList());
    }

    @Test
    void dispatchesEveryNonSelectStatementWithItsPayload() {
        QueryTree update = parser.parseToTree("UPDATE pegawai SET salary = salary * 1.05 WHERE id = 1;");
        assertEquals(QueryNodeType.UPDATE, update.getNodeType());
        assertEquals("pegawai", update.getTableName());
        assertEquals(new ColumnReference(null, "salary"), update.getUpdateColumn());
        assertEquals("*", update.getUpdateExpression().getRoot().getValue());
        assertEquals(1, update.getConditions().size());

        QueryTree insert = parser.parseToTree("INSERT INTO siswa VALUES (1, 'Ayu', 18);");
        assertEquals(QueryNodeType.INSERT, insert.getNodeType());
        assertEquals(List.of("1", "Ayu", "18"),
            insert.getInsertValues().stream().map(v -> v.getRoot().getValue()).toList());

        QueryTree delete = parser.parseToTree("DELETE FROM siswa WHERE id = 1;");
        assertEquals(QueryNodeType.DELETE, delete.getNodeType());
        assertEquals("siswa", delete.getTableName());
        assertEquals(1, delete.getConditions().size());

        QueryTree index = parser.parseToTree("CREATE INDEX idx_siswa_id ON siswa(id);");
        assertEquals(QueryNodeType.CREATE_INDEX, index.getNodeType());
        assertEquals("idx_siswa_id", index.getIndexName());
        assertEquals(new ColumnReference(null, "id"), index.getIndexColumn());
    }

    @Test
    void returnsTransactionKindsWithoutDependingOnCatalogOrOptimization() {
        assertEquals(QueryNodeType.BEGIN, parser.parseToTree("BEGIN;").getNodeType());
        assertEquals(QueryNodeType.BEGIN, parser.parseToTree("BEGIN TRANSACTION;").getNodeType());
        assertEquals(QueryNodeType.COMMIT, parser.parseToTree("COMMIT;").getNodeType());
        assertEquals(QueryNodeType.ABORT, parser.parseToTree("aBoRt;").getNodeType());
    }

    @Test
    void supportsOptionalSemicolonAndTrailingWhitespaceOrComments() {
        for (String sql : List.of("SELECT * FROM a", "UPDATE a SET id = 1", "INSERT INTO a VALUES (1)",
            "DELETE FROM a", "CREATE INDEX idx ON a(id)", "BEGIN", "BEGIN TRANSACTION", "COMMIT", "ABORT")) {
            QueryNodeType type = parser.parseToTree(sql).getNodeType();
            assertEquals(type, parser.parseToTree(sql + ";").getNodeType(), sql);
            assertEquals(type, parser.parseToTree(sql + ";\n -- trailing comment\n\t").getNodeType(), sql);
            assertEquals(type, parser.parseToTree(sql + "\n -- trailing comment").getNodeType(), sql);
        }
    }

    @Test
    void rejectsAdditionalStatementsAndExtraTerminators() {
        assertSyntax("SELECT * FROM a; SELECT * FROM b", "SELECT");
        assertSyntax("UPDATE a SET id = 1; DELETE FROM a", "DELETE");
        assertSyntax("BEGIN; COMMIT;", "COMMIT");
        assertSyntax("ABORT; BEGIN", "BEGIN");
        assertSyntax("SELECT * FROM a;;", ";");
        assertSyntax("COMMIT;;", ";");
        assertSyntax("DELETE FROM a;)", ")");
        assertSyntax("CREATE INDEX idx ON a(id); trailing", "trailing");
    }

    @Test
    void rejectsEmptyCommentOnlyAndNullSourceAsEmptyStatements() {
        for (String sql : List.of("", " \n\t", "-- only a comment")) {
            assertSyntax(sql, "");
        }
        assertSyntax(";", ";");
        QuerySyntaxException error = assertThrows(QuerySyntaxException.class, () -> parser.parseToTree(null));
        assertEquals("", error.getSqlSource());
        assertEquals("", error.getLexeme());
    }

    @Test
    void forwardsSyntaxErrorsFromSelectStatementAndExpressionParsers() {
        assertSyntax("SELECT FROM a", "FROM");
        assertSyntax("SELECT * FROM a WHERE id >", "");
        assertSyntax("SELECT * FROM a ORDER BY", "");
        assertSyntax("UPDATE a WHERE id = 1", "WHERE");
        assertSyntax("UPDATE a SET id = 1 + * 2", "*");
        assertSyntax("INSERT INTO a VALUES (1,)", ")");
        assertSyntax("DELETE FROM a WHERE", "");
        assertSyntax("CREATE INDEX idx ON a()", ")");
        assertSyntax("something", "something");
    }

    @Test
    void forwardsCurrentSqlAndMultilineLocationsToTheCollector() {
        QuerySyntaxException error = assertSyntax("SELECT * FROM a\nWHERE id >\nORDER BY id", "ORDER");
        assertEquals(3, error.getLine());
        assertEquals(1, error.getColumn());
        QuerySyntaxException terminator = assertSyntax("COMMIT;\nBEGIN", "BEGIN");
        assertEquals(2, terminator.getLine());
        assertEquals(1, terminator.getColumn());
    }

    @Test
    void unknownSymbolsWithoutACollectorAreLexicalErrorsBeforeDispatch() {
        for (String sql : List.of("SELECT @ FROM a", "SELECT * FROM a; @", "@UPDATE a SET id = 1")) {
            QueryLexicalException error = assertThrows(QueryLexicalException.class, () -> parser.parseToTree(sql));
            assertEquals(ErrorCategory.LEXICAL_ERROR, error.getCategory());
            assertEquals("@", error.getLexeme());
            assertEquals(sql, error.getSqlSource());
        }
    }

    @Test
    void rethrowsRecordedUnknownSymbolErrorsWithoutDuplicateSyntaxDiagnostics() {
        QueryErrorCollector collector = new QueryErrorCollector();
        ShaylaParser contextual = new ShaylaParser(new ShaylaLexer("", collector));
        String sql = "SELECT @, ? FROM a";

        QueryLexicalException error = assertThrows(QueryLexicalException.class, () -> contextual.parseToTree(sql));

        assertEquals(2, collector.getAllExceptions().size());
        assertSame(error, collector.getAllExceptions().get(0));
        assertEquals(List.of("@", "?"),
            collector.getAllExceptions().stream().map(QueryProcessingException::getLexeme).toList());
        assertTrue(collector.getAllExceptions().stream().allMatch(e -> e.getCategory() == ErrorCategory.LEXICAL_ERROR));
        assertEquals(sql, error.getSqlSource());
        assertEquals(1, error.getLine());
        assertEquals(8, error.getColumn());
    }

    @Test
    void fatalLexerErrorsPropagateOnceWithSourceAndCategory() {
        for (String sql : List.of("SELECT 'unterminated FROM a", "INSERT INTO a VALUES (12.34.56)")) {
            QueryErrorCollector collector = new QueryErrorCollector();
            ShaylaParser contextual = new ShaylaParser(new ShaylaLexer("", collector));

            QueryLexicalException error = assertThrows(QueryLexicalException.class, () -> contextual.parseToTree(sql));

            assertEquals(ErrorCategory.LEXICAL_ERROR, error.getCategory());
            assertEquals(sql, error.getSqlSource());
            assertEquals(List.of(error), collector.getAllExceptions());
        }
    }

    @Test
    void reusesTheInjectedLexerWithNewInputAfterSuccessAndFailure() {
        ShaylaLexer lexer = new ShaylaLexer("stale source");
        IQueryParser entryPoint = new ShaylaParser(lexer);
        assertEquals(QueryNodeType.PROJECT, entryPoint.parseToTree("SELECT * FROM a").getNodeType());
        assertEquals("SELECT * FROM a", lexer.getInput());
        assertThrows(QuerySyntaxException.class, () -> entryPoint.parseToTree("UPDATE a SET"));
        assertThrows(QueryLexicalException.class, () -> entryPoint.parseToTree("SELECT @ FROM a"));
        assertThrows(QueryLexicalException.class, () -> entryPoint.parseToTree("INSERT INTO a VALUES ('"));

        assertEquals(QueryNodeType.ABORT, entryPoint.parseToTree("ABORT").getNodeType());
        QueryTree next = entryPoint.parseToTree("SELECT nama FROM siswa WHERE id = 2 ORDER BY nama");
        assertEquals("SELECT nama FROM siswa WHERE id = 2 ORDER BY nama", lexer.getInput());
        assertEquals(QueryNodeType.SORT, next.getChildren().get(0).getNodeType());
        assertEquals(ExpressionNode.Type.COMPARISON,
            next.getChildren().get(0).getChildren().get(0).getConditions().get(0).getRoot().getType());
    }

    @Test
    void historicalCollectorErrorsArePreservedWithoutBlockingLaterValidInput() {
        QueryErrorCollector collector = new QueryErrorCollector();
        ShaylaParser contextual = new ShaylaParser(new ShaylaLexer("", collector));
        QuerySyntaxException previous = assertThrows(QuerySyntaxException.class, () -> contextual.parseToTree("UPDATE a"));

        assertEquals(QueryNodeType.COMMIT, contextual.parseToTree("COMMIT;").getNodeType());
        assertEquals(List.of(previous), collector.getAllExceptions());
        QueryLexicalException lexical = assertThrows(QueryLexicalException.class,
            () -> contextual.parseToTree("SELECT @ FROM a"));
        assertEquals(List.of(previous, lexical), collector.getAllExceptions());
        assertEquals(QueryNodeType.DELETE, contextual.parseToTree("DELETE FROM a").getNodeType());
        assertEquals(List.of(previous, lexical), collector.getAllExceptions());
    }

    @Test
    void usesTheCurrentCollectorConfiguredOnTheInjectedLexer() {
        ShaylaLexer lexer = new ShaylaLexer("");
        ShaylaParser contextual = new ShaylaParser(lexer);
        assertEquals(QueryNodeType.COMMIT, contextual.parseToTree("COMMIT").getNodeType());
        QueryErrorCollector collector = new QueryErrorCollector();
        lexer.setErrorCollector(collector);

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
            () -> contextual.parseToTree("SELECT FROM a"));

        assertEquals(List.of(error), collector.getAllExceptions());
    }

    @Test
    void acceptsMixedCaseMultilineSqlAndPrintsTheReturnedTree() {
        QueryTree tree = parser.parseToTree("sElEcT A.Id\nFrOm A -- table\nwHeRe A.Id >= 1\nOrDeR bY A.Id dEsC;");

        assertEquals("Total estimated cost: 0\nPROJECT [columns=[A.Id]]\n"
            + "  SORT [orderBy=[A.Id DESC]]\n"
            + "    SELECT [conditions=[(A.Id >= 1)]]\n"
            + "      TABLE_SCAN [table=A]\n", new QueryPlanPrinter().printPlan(tree, 0));
    }

    private static QuerySyntaxException assertSyntax(String sql, String lexeme) {
        QueryErrorCollector collector = new QueryErrorCollector();
        ShaylaParser contextual = new ShaylaParser(new ShaylaLexer("old input", collector));

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class, () -> contextual.parseToTree(sql), sql);

        assertEquals(lexeme, error.getLexeme(), sql);
        assertEquals(sql, error.getSqlSource(), sql);
        assertEquals(ErrorCategory.SYNTAX_ERROR, error.getCategory(), sql);
        assertEquals(List.of(error), collector.getAllExceptions(), sql);
        return error;
    }
}
