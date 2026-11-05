package mdbms.optimizer;

import java.util.ArrayList;
import java.util.List;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.OrderByClause;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.models.query.SortDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectParserClausesTest {
    private final SelectParser parser = new SelectParser(new ExpressionParser());

    @Test
    void repeatedCommasGroupFromLeftToRight() {
        QueryTree outer = source("SELECT * FROM a, b, c;");

        assertEquals(QueryNodeType.CARTESIAN, outer.getNodeType());
        assertEquals(QueryNodeType.CARTESIAN, outer.getChildren().get(0).getNodeType());
        assertScan(outer.getChildren().get(0).getChildren().get(0), "a");
        assertScan(outer.getChildren().get(0).getChildren().get(1), "b");
        assertScan(outer.getChildren().get(1), "c");
    }

    @Test
    void repeatedConditionalJoinsKeepEachOnWithItsJoin() {
        QueryTree outer = source("SELECT * FROM a JOIN b ON a.id = b.aid "
            + "JOIN c ON b.id = c.bid AND c.active = 1;");
        QueryTree inner = outer.getChildren().get(0);

        assertEquals(QueryNodeType.JOIN, outer.getNodeType());
        assertFalse(outer.isNaturalJoin());
        assertEquals("(AND (= b.id c.bid) (= c.active 1))", predicateShape(outer));
        assertScan(outer.getChildren().get(1), "c");
        assertEquals(QueryNodeType.JOIN, inner.getNodeType());
        assertFalse(inner.isNaturalJoin());
        assertEquals("(= a.id b.aid)", predicateShape(inner));
        assertScan(inner.getChildren().get(0), "a");
        assertScan(inner.getChildren().get(1), "b");
    }

    @Test
    void repeatedNaturalJoinsRetainEachUnresolvedMarker() {
        QueryTree outer = source("SELECT * FROM a NATURAL JOIN b NATURAL JOIN c");
        QueryTree inner = outer.getChildren().get(0);

        assertEquals(QueryNodeType.JOIN, outer.getNodeType());
        assertTrue(outer.isNaturalJoin());
        assertTrue(outer.getConditions().isEmpty());
        assertEquals(QueryNodeType.JOIN, inner.getNodeType());
        assertTrue(inner.isNaturalJoin());
        assertTrue(inner.getConditions().isEmpty());
        assertScan(inner.getChildren().get(0), "a");
        assertScan(inner.getChildren().get(1), "b");
        assertScan(outer.getChildren().get(1), "c");
    }

    @Test
    void mixedCommaConditionalAndNaturalJoinFollowSpecificationLeftGrouping() {
        QueryTree natural = source("SELECT * FROM a, b JOIN c ON b.id = c.bid NATURAL JOIN d;");
        QueryTree conditional = natural.getChildren().get(0);
        QueryTree cartesian = conditional.getChildren().get(0);

        assertEquals(QueryNodeType.JOIN, natural.getNodeType());
        assertTrue(natural.isNaturalJoin());
        assertTrue(natural.getConditions().isEmpty());
        assertScan(natural.getChildren().get(1), "d");
        assertEquals(QueryNodeType.JOIN, conditional.getNodeType());
        assertFalse(conditional.isNaturalJoin());
        assertEquals("(= b.id c.bid)", predicateShape(conditional));
        assertScan(conditional.getChildren().get(1), "c");
        assertEquals(QueryNodeType.CARTESIAN, cartesian.getNodeType());
        assertScan(cartesian.getChildren().get(0), "a");
        assertScan(cartesian.getChildren().get(1), "b");
    }

    @Test
    void commaAfterJoinContinuesTheSameLeftFold() {
        QueryTree cartesian = source("SELECT * FROM a NATURAL JOIN b, c JOIN d ON c.id = d.cid")
            .getChildren().get(0);

        assertEquals(QueryNodeType.CARTESIAN, cartesian.getNodeType());
        assertTrue(cartesian.getChildren().get(0).isNaturalJoin());
        assertScan(cartesian.getChildren().get(0).getChildren().get(0), "a");
        assertScan(cartesian.getChildren().get(0).getChildren().get(1), "b");
        assertScan(cartesian.getChildren().get(1), "c");
    }

    @Test
    void whereWrapsSourceInFilterWithTheCompletePredicate() {
        QueryTree filter = source("SELECT nama FROM siswa WHERE (salary + 1) * 2 > 5000 "
            + "AND (umur >= 18 AND aktif = 1);");

        assertEquals(QueryNodeType.SELECT, filter.getNodeType());
        assertEquals("(AND (> (* (+ salary 1) 2) 5000) (AND (>= umur 18) (= aktif 1)))",
            predicateShape(filter));
        assertEquals(1, filter.getChildren().size());
        assertScan(filter.getChildren().get(0), "siswa");
    }

    @Test
    void fullSelectPreservesProjectionFilterAndDescendingSort() {
        QueryTree project = parse("SELECT siswa.nama, siswa.umur FROM siswa "
            + "WHERE umur >= 18 ORDER BY umur DESC;");
        QueryTree sort = project.getChildren().get(0);
        QueryTree filter = sort.getChildren().get(0);

        assertEquals(QueryNodeType.PROJECT, project.getNodeType());
        assertEquals(List.of("siswa.nama", "siswa.umur"),
            project.getProjectionColumns().stream().map(ColumnReference::toString).toList());
        assertEquals(QueryNodeType.SORT, sort.getNodeType());
        assertEquals(1, sort.getOrderByClauses().size());
        assertSortClause(sort.getOrderByClauses().get(0), null, "umur", SortDirection.DESC);
        assertEquals(QueryNodeType.SELECT, filter.getNodeType());
        assertEquals("(>= umur 18)", predicateShape(filter));
        assertScan(filter.getChildren().get(0), "siswa");
    }

    @Test
    void sortDefaultsToAscAndKeepsNonProjectedSortColumnsAvailable() {
        QueryTree project = parse("SELECT nama FROM siswa ORDER BY umur;");
        QueryTree sort = project.getChildren().get(0);

        assertEquals("nama", project.getProjectionColumns().get(0).getColumnName());
        assertEquals(QueryNodeType.SORT, sort.getNodeType());
        assertSortClause(sort.getOrderByClauses().get(0), null, "umur", SortDirection.ASC);
        assertScan(sort.getChildren().get(0), "siswa");
        assertSortClause(source("SELECT nama FROM siswa ORDER BY nama;").getOrderByClauses().get(0),
            null, "nama", SortDirection.ASC);
        assertSortClause(source("SELECT nama FROM siswa ORDER BY nama ASC;").getOrderByClauses().get(0),
            null, "nama", SortDirection.ASC);
    }

    @Test
    void multipleSortKeysPreserveOrderQualifiersDuplicatesAndDirections() {
        QueryTree sort = source("SELECT * FROM siswa ORDER BY siswa.umur DESC, nama ASC, id, nama DESC");
        List<OrderByClause> clauses = sort.getOrderByClauses();

        assertEquals(4, clauses.size());
        assertSortClause(clauses.get(0), "siswa", "umur", SortDirection.DESC);
        assertSortClause(clauses.get(1), null, "nama", SortDirection.ASC);
        assertSortClause(clauses.get(2), null, "id", SortDirection.ASC);
        assertSortClause(clauses.get(3), null, "nama", SortDirection.DESC);
        assertThrows(UnsupportedOperationException.class, () -> clauses.clear());
    }

    @Test
    void fullMixedJoinTreeKeepsOnWhereAndSortAtTheirOwnNodes() {
        QueryTree project = parse("SELECT d.nama, a.id FROM a, b JOIN c ON b.id = c.bid "
            + "NATURAL JOIN d WHERE a.id > 10 AND d.nama <> 'Ayu' ORDER BY c.bid DESC, a.id;");
        QueryTree sort = project.getChildren().get(0);
        QueryTree filter = sort.getChildren().get(0);
        QueryTree natural = filter.getChildren().get(0);
        QueryTree conditional = natural.getChildren().get(0);

        assertEquals(List.of("d.nama", "a.id"),
            project.getProjectionColumns().stream().map(ColumnReference::toString).toList());
        assertEquals(QueryNodeType.SORT, sort.getNodeType());
        assertEquals(2, sort.getOrderByClauses().size());
        assertSortClause(sort.getOrderByClauses().get(0), "c", "bid", SortDirection.DESC);
        assertSortClause(sort.getOrderByClauses().get(1), "a", "id", SortDirection.ASC);
        assertEquals(QueryNodeType.SELECT, filter.getNodeType());
        assertEquals("(AND (> a.id 10) (<> d.nama Ayu))", predicateShape(filter));
        assertTrue(natural.isNaturalJoin());
        assertTrue(natural.getConditions().isEmpty());
        assertScan(natural.getChildren().get(1), "d");
        assertEquals("(= b.id c.bid)", predicateShape(conditional));
        assertEquals(QueryNodeType.CARTESIAN, conditional.getChildren().get(0).getNodeType());
        assertScan(conditional.getChildren().get(1), "c");
        assertTrue(project.getConditions().isEmpty());
        assertTrue(sort.getConditions().isEmpty());
        assertTrue(project.getOrderByClauses().isEmpty());
        assertTrue(filter.getOrderByClauses().isEmpty());
    }

    @Test
    void clausesAndDirectionsRemainCaseInsensitiveAcrossLinesAndComments() {
        QueryTree sort = source("sElEcT S.Nama\nFrOm S -- source\nwHeRe S.Umur >= 18\n"
            + "oRdEr -- clause\n bY S.Umur dEsC, S.Nama aSc;");
        QueryTree filter = sort.getChildren().get(0);

        assertEquals(QueryNodeType.SORT, sort.getNodeType());
        assertSortClause(sort.getOrderByClauses().get(0), "S", "Umur", SortDirection.DESC);
        assertSortClause(sort.getOrderByClauses().get(1), "S", "Nama", SortDirection.ASC);
        assertEquals(QueryNodeType.SELECT, filter.getNodeType());
        assertEquals("(>= S.Umur 18)", predicateShape(filter));
        assertScan(filter.getChildren().get(0), "S");
    }

    @Test
    void multilineAndSingleLineQueriesProduceEquivalentTrees() {
        String sql = "SELECT a.nama, b.id FROM a JOIN b ON a.id = b.aid "
            + "WHERE a.umur >= 18 ORDER BY b.id DESC, a.nama";
        String multiline = sql.replace(" FROM ", "\nFROM ").replace(" ON ", "\nON ")
            .replace(" WHERE ", "\nWHERE ").replace(" ORDER BY ", "\nORDER\nBY ");

        assertEquals(treeShape(parse(sql)), treeShape(parse(multiline)));
    }

    @Test
    void fullSelectLeavesSemicolonAndNextStatementToFacade() {
        TokenStream stream = stream("SELECT nama FROM siswa WHERE umur >= 18 ORDER BY umur DESC; "
            + "SELECT * FROM guru;");

        parser.parseSelect(stream);

        stream.expect(TokenType.semicolon);
        QueryTree second = parser.parseSelect(stream);
        assertScan(second.getChildren().get(0), "guru");
        stream.expect(TokenType.semicolon);
        assertTrue(stream.isAtEnd());
        TokenStream eof = stream("SELECT * FROM siswa WHERE umur = 18 ORDER BY umur");
        parser.parseSelect(eof);
        assertTrue(eof.isAtEnd());
    }

    @Test
    void whereRequiresACompleteCondition() {
        assertError("SELECT * FROM a WHERE", "");
        assertError("SELECT * FROM a WHERE;", ";");
        assertError("SELECT * FROM a WHERE ORDER BY id", "ORDER");
        assertError("SELECT * FROM a WHERE id >", "");
        assertError("SELECT * FROM a WHERE id", "id");
        assertError("SELECT * FROM a WHERE id = 1 AND;", ";");
        assertError("SELECT * FROM a WHERE id = 1, x = 2", ",");
        assertError("SELECT * FROM a WHERE (id = 1", "");
        assertError("SELECT * FROM a WHERE id = 1)", ")");
        assertError("SELECT * FROM a WHERE id = 1 OR x = 2", "OR");
    }

    @Test
    void orderByRequiresByAndAColumnWithAtMostOneDirection() {
        assertError("SELECT * FROM a ORDER", "");
        assertError("SELECT * FROM a ORDER id", "id");
        assertError("SELECT * FROM a ORDER BY", "");
        assertError("SELECT * FROM a ORDER BY;", ";");
        assertError("SELECT * FROM a ORDER BY DESC", "DESC");
        assertError("SELECT * FROM a ORDER BY ASC", "ASC");
        assertError("SELECT * FROM a ORDER BY , id", ",");
        assertError("SELECT * FROM a ORDER BY id,", "");
        assertError("SELECT * FROM a ORDER BY id, DESC", "DESC");
        assertError("SELECT * FROM a ORDER BY *", "*");
        assertError("SELECT * FROM a ORDER BY a.*", "*");
        assertError("SELECT * FROM a ORDER BY a.", "");
        assertError("SELECT * FROM a ORDER BY a.id.x", ".");
        assertError("SELECT * FROM a ORDER BY id ASC DESC", "DESC");
        assertError("SELECT * FROM a ORDER BY id DESC ASC", "ASC");
        assertError("SELECT * FROM a ORDER BY id name", "name");
        assertError("SELECT * FROM a ORDER BY id + 1", "+");
        assertError("SELECT * FROM a ORDER BY 1", "1");
        assertError("SELECT * FROM a ORDER BY 'id'", "id");
    }

    @Test
    void rejectsRepeatedAndOutOfOrderClauses() {
        assertError("SELECT * WHERE id = 1 FROM a", "WHERE");
        assertError("SELECT * FROM a WHERE id = 1 WHERE x = 2", "WHERE");
        assertError("SELECT * FROM a WHERE id = 1 JOIN b ON a.id = b.id", "JOIN");
        assertError("SELECT * FROM a WHERE id = 1 NATURAL JOIN b", "NATURAL");
        assertError("SELECT * FROM a WHERE id = 1 FROM b", "FROM");
        assertError("SELECT * FROM a ORDER BY id WHERE x = 1", "WHERE");
        assertError("SELECT * FROM a ORDER BY id ORDER BY id", "ORDER");
        assertError("SELECT * FROM a ORDER BY id JOIN b ON a.id = b.id", "JOIN");
        assertError("SELECT * FROM a ORDER BY id FROM b", "FROM");
        assertError("SELECT * FROM a JOIN b ON a.id = b.id ON a.x = b.x", "ON");
        assertError("SELECT * FROM a WHERE id = 1 ORDER id", "ORDER");
    }

    @Test
    void errorsAfterJoinChainsPointToTheMissingOrUnexpectedPart() {
        assertError("SELECT * FROM a, b JOIN c WHERE b.id = c.id", "WHERE");
        assertError("SELECT * FROM a, b JOIN c ON ORDER BY id", "ORDER");
        assertError("SELECT * FROM a JOIN b ON a.id = b.id,", "");
        assertError("SELECT * FROM a NATURAL JOIN b NATURAL c", "c");
        assertError("SELECT * FROM a JOIN b ON a.id = b.id NATURAL JOIN", "");
    }

    @Test
    void diagnosticsPreserveMultilineSourceTokenLocationAndOneCollectedException() {
        QuerySyntaxException error = assertError("SELECT *\nFROM a\nWHERE id >= 1\nORDER BY a.", "");

        assertEquals(4, error.getLine());
        assertEquals(12, error.getColumn());
        QuerySyntaxException predicateError = assertError("SELECT * FROM a\nWHERE id >\nORDER BY id", "ORDER");
        assertEquals(3, predicateError.getLine());
        assertEquals(1, predicateError.getColumn());
    }

    @Test
    void sharedExpressionParserSuppliesEachOnAndWhereTreeDirectly() {
        class TrackingParser extends ExpressionParser {
            final List<ExpressionTree> results = new ArrayList<>();

            @Override
            public ExpressionTree parsePredicate(TokenStream stream) {
                ExpressionTree result = super.parsePredicate(stream);
                results.add(result);
                return result;
            }
        }
        TrackingParser expression = new TrackingParser();
        QueryTree project = new SelectParser(expression).parseSelect(stream("SELECT * FROM a "
            + "JOIN b ON a.id = b.id JOIN c ON b.id = c.id WHERE a.active = 1 ORDER BY c.id"));
        QueryTree filter = project.getChildren().get(0).getChildren().get(0);
        QueryTree outerJoin = filter.getChildren().get(0);
        QueryTree innerJoin = outerJoin.getChildren().get(0);

        assertEquals(3, expression.results.size());
        assertSame(expression.results.get(0), innerJoin.getConditions().get(0));
        assertSame(expression.results.get(1), outerJoin.getConditions().get(0));
        assertSame(expression.results.get(2), filter.getConditions().get(0));
    }

    @Test
    void parserCanBeReusedAfterWhereAndOrderByFailuresWithoutRetainingClauses() {
        assertThrows(QuerySyntaxException.class, () -> parse("SELECT * FROM a WHERE"));
        assertThrows(QuerySyntaxException.class, () -> parse("SELECT * FROM a ORDER BY"));
        assertEquals(QueryNodeType.SORT, source("SELECT * FROM a WHERE id = 1 ORDER BY id").getNodeType());
        assertScan(source("SELECT * FROM a"), "a");
        QueryTree filter = source("SELECT * FROM a WHERE id = 2");
        assertEquals(QueryNodeType.SELECT, filter.getNodeType());
        assertEquals("(= id 2)", predicateShape(filter));
        assertTrue(filter.getOrderByClauses().isEmpty());
    }

    @Test
    void contextualKeywordFragmentsRemainUsableAsColumnNames() {
        QueryTree project = parse("SELECT order, descending, ordering.DESC, ascending FROM ordering WHERE order = 1");

        assertEquals(List.of("order", "descending", "ordering.DESC", "ascending"),
            project.getProjectionColumns().stream().map(ColumnReference::toString).toList());
        assertEquals("(= order 1)", predicateShape(project.getChildren().get(0)));
        QueryTree sort = source("SELECT order FROM ordering WHERE order = 1 "
            + "ORDER BY order DESC, ordering.DESC ASC, ascending");
        assertSortClause(sort.getOrderByClauses().get(0), null, "order", SortDirection.DESC);
        assertSortClause(sort.getOrderByClauses().get(1), "ordering", "DESC", SortDirection.ASC);
        assertSortClause(sort.getOrderByClauses().get(2), null, "ascending", SortDirection.ASC);
    }

    @Test
    void wrongStatementAndEmptyStreamReportSyntaxErrors() {
        assertError("", "");
        assertError("FROM a", "FROM");
        assertError("UPDATE a SET id = 1", "UPDATE");
    }

    private QueryTree parse(String sql) {
        return parser.parseSelect(stream(sql));
    }

    private QueryTree source(String sql) {
        return parse(sql).getChildren().get(0);
    }

    private static TokenStream stream(String sql) {
        return new ShaylaLexer(sql).tokenizeToStream();
    }

    private static void assertScan(QueryTree tree, String table) {
        assertEquals(QueryNodeType.TABLE_SCAN, tree.getNodeType());
        assertEquals(table, tree.getTableName());
        assertTrue(tree.getChildren().isEmpty());
    }

    private static String predicateShape(QueryTree tree) {
        assertEquals(1, tree.getConditions().size());
        return expressionShape(tree.getConditions().get(0).getRoot());
    }

    private static void assertSortClause(OrderByClause clause, String table, String column,
                                         SortDirection direction) {
        assertEquals(table, clause.getField().getTableName());
        assertEquals(column, clause.getField().getColumnName());
        assertEquals(direction, clause.getDirection());
    }

    private static String expressionShape(ExpressionNode node) {
        return switch (node.getType()) {
            case BINARY_OPERATION, COMPARISON, LOGICAL_AND -> "(" + node.getValue() + " "
                + expressionShape(node.getLeft()) + " " + expressionShape(node.getRight()) + ")";
            default -> node.getTableName() == null ? node.getValue() : node.getTableName() + "." + node.getValue();
        };
    }

    private static String treeShape(QueryTree tree) {
        return tree.getNodeType() + ":" + tree.getTableName() + ":" + tree.isNaturalJoin()
            + ":" + tree.getProjectionColumns() + ":"
            + tree.getConditions().stream().map(c -> expressionShape(c.getRoot())).toList() + ":"
            + tree.getOrderByClauses().stream().map(c -> c.getField() + " " + c.getDirection()).toList() + ":"
            + tree.getChildren().stream().map(SelectParserClausesTest::treeShape).toList();
    }

    private static QuerySyntaxException assertError(String sql, String lexeme) {
        QueryErrorCollector collector = new QueryErrorCollector();
        TokenStream stream = new ShaylaLexer(sql, collector).tokenizeToStream();
        SelectParser contextual = new SelectParser(new ExpressionParser(sql, collector), sql, collector);

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
            () -> contextual.parseSelect(stream), sql);

        assertEquals(lexeme, error.getLexeme(), sql);
        assertEquals(ErrorCategory.SYNTAX_ERROR, error.getCategory(), sql);
        assertEquals(sql, error.getSqlSource(), sql);
        assertEquals(List.of(error), collector.getAllExceptions(), sql);
        return error;
    }
}
