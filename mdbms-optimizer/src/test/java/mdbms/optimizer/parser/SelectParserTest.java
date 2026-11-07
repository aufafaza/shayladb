package mdbms.optimizer.parser;

import java.util.List;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.exception.ErrorCategory;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QuerySyntaxException;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.lexer.TokenStream;
import mdbms.optimizer.lexer.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectParserTest {
    private final SelectParser parser = new SelectParser(new ExpressionParser());

    @Test
    void selectStarPreservesWildcardForBinderAndBuildsSingleScan() {
        QueryTree project = parse("SELECT * FROM siswa;");

        assertEquals(QueryNodeType.PROJECT, project.getNodeType());
        assertNull(project.getTableName());
        assertEquals(1, project.getProjectionColumns().size());
        ColumnReference star = project.getProjectionColumns().get(0);
        assertTrue(star.isWildcard());
        assertNull(star.getTableName());
        assertEquals("*", star.getColumnName());
        assertTrue(project.getConditions().isEmpty());
        assertEquals(1, project.getChildren().size());
        assertScan(project.getChildren().get(0), "siswa");
    }

    @Test
    void preservesColumnOrderDuplicatesAndQualifiers() {
        QueryTree project = parse("SELECT siswa.nama, umur, siswa.nama, id FROM siswa");
        List<ColumnReference> columns = project.getProjectionColumns();

        assertEquals(List.of("siswa.nama", "umur", "siswa.nama", "id"),
            columns.stream().map(ColumnReference::toString).toList());
        assertEquals("siswa", columns.get(0).getTableName());
        assertEquals("nama", columns.get(0).getColumnName());
        assertNull(columns.get(1).getTableName());
        assertFalse(columns.get(1).isWildcard());
        assertNull(project.getTableName());
        assertScan(project.getChildren().get(0), "siswa");
        assertThrows(UnsupportedOperationException.class, () -> columns.add(columns.get(0)));
    }

    @Test
    void commaBuildsCartesianWithSourcesInWrittenOrder() {
        QueryTree cartesian = source("SELECT a.id FROM a, b;");

        assertEquals(QueryNodeType.CARTESIAN, cartesian.getNodeType());
        assertEquals(2, cartesian.getChildren().size());
        assertScan(cartesian.getChildren().get(0), "a");
        assertScan(cartesian.getChildren().get(1), "b");
        assertTrue(cartesian.getConditions().isEmpty());
    }

    @Test
    void conditionalJoinPreservesQualifiedColumnOperands() {
        QueryTree join = source("SELECT a.id FROM a JOIN b ON a.id = b.aid;");

        assertEquals(QueryNodeType.JOIN, join.getNodeType());
        assertFalse(join.isNaturalJoin());
        assertEquals(2, join.getChildren().size());
        assertScan(join.getChildren().get(0), "a");
        assertScan(join.getChildren().get(1), "b");
        assertEquals(1, join.getConditions().size());
        ExpressionNode on = join.getConditions().get(0).getRoot();
        assertEquals(ExpressionNode.Type.COMPARISON, on.getType());
        assertEquals("=", on.getValue());
        assertColumn(on.getLeft(), "a", "id");
        assertColumn(on.getRight(), "b", "aid");
    }

    @Test
    void joinUsesExpressionParserForArithmeticAndGroupedConditions() {
        QueryTree join = source("SELECT * FROM a JOIN b ON "
            + "(a.salary + 1) * 2 >= b.salary AND (a.id = b.id AND b.active <> 0)");
        ExpressionNode on = join.getConditions().get(0).getRoot();

        assertEquals(ExpressionNode.Type.LOGICAL_AND, on.getType());
        assertEquals(">=", on.getLeft().getValue());
        assertEquals("*", on.getLeft().getLeft().getValue());
        assertEquals("+", on.getLeft().getLeft().getLeft().getValue());
        assertEquals(ExpressionNode.Type.LOGICAL_AND, on.getRight().getType());
        assertEquals("=", on.getRight().getLeft().getValue());
        assertEquals("<>", on.getRight().getRight().getValue());
    }

    @Test
    void naturalJoinUsesDiagramJoinKindWithMarkerForBinder() {
        QueryTree natural = source("SELECT * FROM a NATURAL JOIN b;");

        assertEquals(QueryNodeType.JOIN, natural.getNodeType());
        assertTrue(natural.isNaturalJoin());
        assertEquals(2, natural.getChildren().size());
        assertScan(natural.getChildren().get(0), "a");
        assertScan(natural.getChildren().get(1), "b");
        assertTrue(natural.getConditions().isEmpty());
    }

    @Test
    void acceptsMixedCaseKeywordsWhitespaceAndPreservesIdentifierSpelling() {
        QueryTree join = source("sElEcT A.Id\n fRoM A\n nAtUrAl\tJoIn B;");

        assertEquals(QueryNodeType.JOIN, join.getNodeType());
        assertTrue(join.isNaturalJoin());
        assertScan(join.getChildren().get(0), "A");
        assertScan(join.getChildren().get(1), "B");
        assertEquals(QueryNodeType.JOIN, source("select * from a jOiN b oN a.id=b.id").getNodeType());
    }

    @Test
    void leavesStatementTerminatorAndFollowingStatementToFacade() {
        TokenStream stream = stream("SELECT * FROM siswa; SELECT id FROM guru;");

        parser.parseSelect(stream);

        assertEquals(TokenType.semicolon, stream.peek().getType());
        stream.advance();
        assertEquals("id", parser.parseSelect(stream).getProjectionColumns().get(0).getColumnName());
        stream.expect(TokenType.semicolon);
        assertTrue(stream.isAtEnd());
        TokenStream noSemicolon = stream("SELECT id FROM siswa");
        parser.parseSelect(noSemicolon);
        assertTrue(noSemicolon.isAtEnd());
    }

    @Test
    void rejectsMissingOrMalformedProjectionItems() {
        assertError("SELECT FROM siswa", "FROM");
        assertError("SELECT , id FROM siswa", ",");
        assertError("SELECT id, FROM siswa", "FROM");
        assertError("SELECT a. FROM siswa", "FROM");
        assertError("SELECT a.id.x FROM siswa", ".");
        assertError("SELECT *, id FROM siswa", ",");
        assertError("SELECT id, * FROM siswa", "*");
        assertError("SELECT a.* FROM a", "*");
        assertError("SELECT id nama FROM siswa", "nama");
        assertError("SELECT id + 1 FROM siswa", "+");
    }

    @Test
    void rejectsMissingFromAndMissingTableSources() {
        assertError("SELECT id", "");
        assertError("SELECT * FROM", "");
        assertError("SELECT * FROM ;", ";");
        assertError("SELECT * FROM a,;", ";");
        assertError("SELECT * FROM JOIN b ON a.id = b.id", "JOIN");
        assertError("SELECT * FROM a, JOIN b ON a.id = b.id", "JOIN");
        assertError("SELECT * FROM a JOIN ON a.id = b.id", "ON");
        assertError("SELECT * FROM a NATURAL JOIN;", ";");
    }

    @Test
    void conditionalJoinRequiresOnAndACompletePredicate() {
        assertError("SELECT * FROM a JOIN b;", ";");
        assertError("SELECT * FROM a JOIN b a.id = b.id", "a");
        assertError("SELECT * FROM a JOIN b ON;", ";");
        assertError("SELECT * FROM a JOIN b ON a.id =;", ";");
        assertError("SELECT * FROM a JOIN b ON a.id", "a");
        assertError("SELECT * FROM a JOIN b ON a.id = b.id AND;", ";");
        assertError("SELECT * FROM a JOIN b ON (a.id = b.id;", ";");
    }

    @Test
    void rejectsMalformedNaturalJoinAndExplicitOnForNaturalJoin() {
        assertError("SELECT * FROM a NATURAL b", "b");
        assertError("SELECT * FROM a NATURAL JOIN b ON a.id = b.id", "ON");
    }

    @Test
    void rejectsUnexpectedSuffixes() {
        assertError("SELECT * FROM a b", "b");
        assertError("SELECT * FROM a)", ")");
        assertError("SELECT * FROM a ON id = 1", "ON");
    }

    @Test
    void diagnosticsKeepMultilineLocationAndCollectorWithoutDuplicates() {
        QuerySyntaxException error = assertError("SELECT * FROM a\n NATURAL b", "b");

        assertEquals(2, error.getLine());
        assertEquals(10, error.getColumn());
    }

    @Test
    void parserIsReusableAfterFailure() {
        assertThrows(QuerySyntaxException.class, () -> parser.parseSelect(stream("SELECT * FROM a JOIN")));
        assertScan(source("SELECT * FROM siswa"), "siswa");
        assertTrue(source("SELECT * FROM a NATURAL JOIN b").isNaturalJoin());
    }

    private QueryTree parse(String sql) {
        QueryTree tree = parser.parseSelect(stream(sql));
        System.out.println("=== QueryTree for: " + sql + " ===");
        tree.printQueryTree();
        return tree;
    }

    private QueryTree source(String sql) {
        return parse(sql).getChildren().get(0);
    }

    private static TokenStream stream(String sql) {
        return new ShaylaLexer(sql).tokenizeToStream();
    }

    private static void assertScan(QueryTree node, String table) {
        assertEquals(QueryNodeType.TABLE_SCAN, node.getNodeType());
        assertEquals(table, node.getTableName());
        assertTrue(node.getChildren().isEmpty());
        assertTrue(node.getConditions().isEmpty());
    }

    private static void assertColumn(ExpressionNode node, String table, String column) {
        assertEquals(ExpressionNode.Type.COLUMN_REFERENCE, node.getType());
        assertEquals(table, node.getTableName());
        assertEquals(column, node.getValue());
    }

    private static QuerySyntaxException assertError(String sql, String offendingLexeme) {
        QueryErrorCollector collector = new QueryErrorCollector();
        TokenStream stream = new ShaylaLexer(sql, collector).tokenizeToStream();
        SelectParser contextual = new SelectParser(new ExpressionParser(sql, collector), sql, collector);

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
            () -> contextual.parseSelect(stream), sql);

        assertEquals(offendingLexeme, error.getLexeme(), sql);
        assertEquals(ErrorCategory.SYNTAX_ERROR, error.getCategory(), sql);
        assertEquals(sql, error.getSqlSource(), sql);
        assertEquals(List.of(error), collector.getAllExceptions(), sql);
        return error;
    }
}
