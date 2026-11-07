package mdbms.optimizer;

import java.util.List;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.OrderByClause;
import mdbms.models.query.ParsedQuery;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.lexer.TokenStream;
import mdbms.optimizer.parser.ExpressionParser;
import mdbms.optimizer.parser.SelectParser;
import mdbms.optimizer.parser.StatementParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryPlanPrinterTest {
    private final QueryPlanPrinter printer = new QueryPlanPrinter();

    @Test
    void printsASingleNodeWithItsTableAlgorithmAndSuppliedTotalCost() {
        QueryTree scan = scan("siswa", PhysicalAlgorithm.LINEAR_SCAN, 999);

        assertEquals("Total estimated cost: 7\nTABLE_SCAN [table=siswa, algorithm=LINEAR_SCAN]\n",
            printer.printPlan(scan, 7));
    }

    @Test
    void traversesBranchesInWrittenOrderWithTwoSpacesPerDepth() {
        QueryTree cartesian = new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(scan("b"), scan("a")), 0);
        QueryTree project = new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(cartesian), 0,
            List.of(new ColumnReference("b", "name"), new ColumnReference("a", "id")), false);

        assertEquals("Total estimated cost: 10\n"
            + "PROJECT [columns=[b.name, a.id]]\n"
            + "  CARTESIAN\n"
            + "    TABLE_SCAN [table=b]\n"
            + "    TABLE_SCAN [table=a]\n", printer.printPlan(project, 10));
    }

    @Test
    void printsAFullParsedSelectIncludingSortKeysAndPredicate() {
        QueryTree plan = select("SELECT siswa.nama, siswa.umur FROM siswa WHERE umur >= 18 "
            + "ORDER BY umur DESC, nama;");

        assertEquals("Total estimated cost: 20\n"
            + "PROJECT [columns=[siswa.nama, siswa.umur]]\n"
            + "  SORT [orderBy=[umur DESC, nama ASC]]\n"
            + "    SELECT [conditions=[(umur >= 18)]]\n"
            + "      TABLE_SCAN [table=siswa]\n", printer.printPlan(plan, 20));
    }

    @Test
    void distinguishesNaturalJoinFromConditionalJoinAndPreservesChildOrder() {
        QueryTree plan = select("SELECT * FROM a JOIN b ON a.id = b.aid NATURAL JOIN c;");
        String text = printer.printPlan(plan, 30);

        assertEquals("Total estimated cost: 30\n"
            + "PROJECT [columns=[*]]\n"
            + "  JOIN [natural=true]\n"
            + "    JOIN [conditions=[(a.id = b.aid)]]\n"
            + "      TABLE_SCAN [table=a]\n"
            + "      TABLE_SCAN [table=b]\n"
            + "    TABLE_SCAN [table=c]\n", text);
    }

    @Test
    void printsEveryAvailablePhysicalAlgorithmWithoutInventingADefaultChoice() {
        for (PhysicalAlgorithm algorithm : PhysicalAlgorithm.values()) {
            String text = printer.printPlan(scan("a", algorithm, 0), 0);
            if (algorithm == PhysicalAlgorithm.DEFAULT) {
                assertEquals("Total estimated cost: 0\nTABLE_SCAN [table=a]\n", text);
            } else {
                assertTrue(text.contains("algorithm=" + algorithm), algorithm.toString());
            }
        }
        assertEquals("Total estimated cost: 0\nTABLE_SCAN [table=a]\n",
            printer.printPlan(scan("a", null, 0), 0));
    }

    @Test
    void preservesProjectionOrderDuplicatesAndWildcard() {
        assertTrue(printer.printPlan(select("SELECT b.id, id, b.id FROM b"), 0)
            .contains("columns=[b.id, id, b.id]"));
        assertTrue(printer.printPlan(select("SELECT * FROM b"), 0).contains("columns=[*]"));
    }

    @Test
    void parenthesizesArithmeticWithItsOriginalGroupingAndAssociativity() {
        ExpressionParser expressions = new ExpressionParser();

        assertEquals("(5 + (3 * 2))", printer.printExpression(expressions.parseExpression(stream("5 + 3 * 2"))));
        assertEquals("((5 + 3) * 2)", printer.printExpression(expressions.parseExpression(stream("(5 + 3) * 2"))));
        assertEquals("((5 - 3) - 2)", printer.printExpression(expressions.parseExpression(stream("5 - 3 - 2"))));
        assertEquals("(5 - (3 - 2))", printer.printExpression(expressions.parseExpression(stream("5 - (3 - 2)"))));
        assertEquals("(8 / (4 / 2))", printer.printExpression(expressions.parseExpression(stream("8 / (4 / 2)"))));
    }

    @Test
    void parenthesizesPredicatesAndQuotesTextSoTheirMeaningIsPreserved() {
        ExpressionTree predicate = new ExpressionParser().parsePredicate(stream(
            "salary * 1.05 > 5000 AND (a.id = b.id AND nama <> 'Ayu')"));

        assertEquals("(((salary * 1.05) > 5000) AND ((a.id = b.id) AND (nama <> 'Ayu')))",
            printer.printExpression(predicate));
        for (String operator : List.of("=", "<>", ">", ">=", "<", "<=")) {
            assertEquals("(a.id " + operator + " b.id)", printer.printExpression(
                new ExpressionParser().parsePredicate(stream("a.id " + operator + " b.id"))));
        }
    }

    @Test
    void preservesLiteralTypesEmptyTextQuotesAndNumericSpelling() {
        ExpressionParser expressions = new ExpressionParser();

        assertEquals("''", printer.printExpression(expressions.parseExpression(stream("''"))));
        assertEquals("'it''s fine'", printer.printExpression(expressions.parseExpression(stream("'it''s fine'"))));
        assertEquals("'18'", printer.printExpression(expressions.parseExpression(stream("'18'"))));
        assertEquals("18", printer.printExpression(expressions.parseExpression(stream("18"))));
        assertEquals("3.1400", printer.printExpression(expressions.parseExpression(stream("3.1400"))));
        assertEquals("123456789012345678901234567890", printer.printExpression(expressions.parseExpression(
            stream("123456789012345678901234567890"))));
    }

    @Test
    void escapesDecodedControlCharactersAndBackslashesWithoutBreakingTreeLines() {
        ExpressionTree value = new ExpressionTree(ExpressionNode.literal(ExpressionNode.Type.STRING_LITERAL,
            "a\nb\rc\td\\e'\u0001"));

        assertEquals("'a\\nb\\rc\\td\\\\e''\\u0001'", printer.printExpression(value));
        QueryTree insert = statement("INSERT INTO a VALUES ('line\\nnext', 'tab\\tend')");
        assertEquals("Total estimated cost: 0\nINSERT [table=a, values=['line\\nnext', 'tab\\tend']]\n",
            printer.printPlan(insert, 0));
    }

    @Test
    void printsMultipleConditionsWithoutCombiningOrDroppingThem() {
        ExpressionParser expressions = new ExpressionParser();
        QueryTree filter = new QueryTree(QueryNodeType.SELECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(expressions.parsePredicate(stream("a.id = 1")), expressions.parsePredicate(stream("b.id > 2"))),
            List.of(scan("a")), 0);

        assertTrue(printer.printPlan(filter, 0).contains("conditions=[(a.id = 1), (b.id > 2)]"));
    }

    @Test
    void printsUpdateAssignmentAndWhereAsSeparateParameters() {
        QueryTree update = statement("UPDATE pegawai SET salary = salary * 1.05 WHERE id = 1");

        assertEquals("Total estimated cost: 5\n"
            + "UPDATE [table=pegawai, conditions=[(id = 1)], set=salary = (salary * 1.05)]\n",
            printer.printPlan(update, 5));
    }

    @Test
    void printsInsertLiteralTypesInTheirWrittenOrder() {
        assertEquals("Total estimated cost: 0\nINSERT [table=siswa, values=[1, 'Ayu', 18, 3.1400, '']]\n",
            printer.printPlan(statement("INSERT INTO siswa VALUES (1, 'Ayu', 18, 3.1400, '')"), 0));
    }

    @Test
    void printsDeleteAndTransactionNodeKinds() {
        assertEquals("Total estimated cost: 0\nDELETE [table=a]\n",
            printer.printPlan(statement("DELETE FROM a"), 0));
        assertEquals("Total estimated cost: 0\nDELETE [table=a, conditions=[(id = 1)]]\n",
            printer.printPlan(statement("DELETE FROM a WHERE id = 1"), 0));
        for (String command : List.of("BEGIN", "COMMIT", "ABORT")) {
            assertEquals("Total estimated cost: 0\n" + command + "\n",
                printer.printPlan(statement(command), 0));
        }
    }

    @Test
    void printsIndexNameTableAndColumnWhenAvailable() {
        assertEquals("Total estimated cost: 0\nCREATE_INDEX [table=siswa, index=idx_siswa_id, indexColumn=id]\n",
            printer.printPlan(statement("CREATE INDEX idx_siswa_id ON siswa(id)"), 0));
        assertTrue(printer.printPlan(scan("a", PhysicalAlgorithm.INDEX_SCAN, 0), 1)
            .contains("algorithm=INDEX_SCAN"));
    }

    @Test
    void labelsBeforeAndAfterPlansWithTheirOwnAuthoritativeCosts() {
        QueryTree before = scan("a", PhysicalAlgorithm.LINEAR_SCAN, 999);
        QueryTree after = scan("a", PhysicalAlgorithm.INDEX_SCAN, 888);

        assertEquals("Before optimization\nTotal estimated cost: 30\n"
            + "TABLE_SCAN [table=a, algorithm=LINEAR_SCAN]\n\n"
            + "After optimization\nTotal estimated cost: 7\n"
            + "TABLE_SCAN [table=a, algorithm=INDEX_SCAN]\n",
            printer.printPlans(before, 30, after, 7));
    }

    @Test
    void neverSumsNodeCostsOrUsesTheRootCostAsTheTotal() {
        QueryTree root = new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(scan("a", PhysicalAlgorithm.DEFAULT, 100),
                scan("b", PhysicalAlgorithm.DEFAULT, 200)), 500);

        assertEquals("Total estimated cost: 7\nCARTESIAN\n  TABLE_SCAN [table=a]\n  TABLE_SCAN [table=b]\n",
            printer.printPlan(root, 7));
        assertTrue(printer.printPlan(root, Integer.MAX_VALUE).startsWith("Total estimated cost: 2147483647\n"));
    }

    @Test
    void printingDoesNotMutatePlansCostsAlgorithmsListsOrExpressionRoots() {
        QueryTree before = select("SELECT a.id, a.id FROM a JOIN b ON a.id = b.id "
            + "WHERE a.id > 1 ORDER BY a.id DESC");
        QueryTree sort = before.getChildren().get(0);
        QueryTree filter = sort.getChildren().get(0);
        QueryTree join = filter.getChildren().get(0);
        QueryTree after = statement("UPDATE a SET id = id + 1 WHERE id = 1");
        before.setEstimatedCost(70);
        join.setAlgorithm(PhysicalAlgorithm.HASH_JOIN);
        join.setEstimatedCost(60);
        after.setEstimatedCost(40);
        List<QueryTree> children = join.getChildren();
        List<ColumnReference> columns = before.getProjectionColumns();
        List<OrderByClause> orderBy = sort.getOrderByClauses();
        ExpressionNode where = filter.getConditions().get(0).getRoot();
        ExpressionNode on = join.getConditions().get(0).getRoot();
        ExpressionNode assignment = after.getUpdateExpression().getRoot();
        ExpressionNode updateWhere = after.getConditions().get(0).getRoot();

        String first = printer.printPlans(before, 11, after, 8);
        assertEquals(first, printer.printPlans(before, 11, after, 8));

        assertSame(children, join.getChildren());
        assertEquals(List.of("a", "b"), children.stream().map(QueryTree::getTableName).toList());
        assertSame(columns, before.getProjectionColumns());
        assertSame(orderBy, sort.getOrderByClauses());
        assertSame(where, filter.getConditions().get(0).getRoot());
        assertSame(on, join.getConditions().get(0).getRoot());
        assertSame(assignment, after.getUpdateExpression().getRoot());
        assertSame(updateWhere, after.getConditions().get(0).getRoot());
        assertEquals(70, before.getEstimatedCost());
        assertEquals(60, join.getEstimatedCost());
        assertEquals(40, after.getEstimatedCost());
        assertEquals(PhysicalAlgorithm.HASH_JOIN, join.getAlgorithm());
    }

    @Test
    void sharedChildrenArePrintedForEachParentPosition() {
        QueryTree scan = scan("a");
        QueryTree root = new QueryTree(QueryNodeType.CARTESIAN, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(scan, scan), 0);

        assertEquals("Total estimated cost: 0\nCARTESIAN\n  TABLE_SCAN [table=a]\n  TABLE_SCAN [table=a]\n",
            printer.printPlan(root, 0));
    }

    @Test
    void emptyPayloadOperationsAndDeepPlansCanBeTraversed() {
        QueryTree leaf = new QueryTree(QueryNodeType.LIMIT, PhysicalAlgorithm.DEFAULT, null,
            List.of(), List.of(), 0);
        assertEquals("Total estimated cost: 0\nLIMIT\n", printer.printPlan(leaf, 0));
        QueryTree root = leaf;
        for (int i = 0; i < 200; i++) {
            root = new QueryTree(QueryNodeType.PROJECT, PhysicalAlgorithm.DEFAULT, null,
                List.of(), List.of(root), 0);
        }
        String text = printer.printPlan(root, 0);
        assertEquals(202, text.lines().count());
        assertTrue(text.endsWith("  ".repeat(200) + "LIMIT\n"));
    }

    @Test
    void rejectsMissingInputsNegativeCostsAndMalformedExpressionRoots() {
        QueryTree scan = scan("a");
        assertThrows(NullPointerException.class, () -> printer.printPlan(null, 0));
        assertThrows(IllegalArgumentException.class, () -> printer.printPlan(scan, -1));
        assertThrows(NullPointerException.class, () -> printer.printPlans(scan, 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> printer.printPlans(scan, 0, scan, -1));
        assertThrows(NullPointerException.class, () -> printer.printExpression(null));
        assertThrows(IllegalArgumentException.class, () -> printer.printExpression(new ExpressionTree(null)));
        QueryTree noType = new QueryTree(null, PhysicalAlgorithm.DEFAULT, null, List.of(), List.of(), 0);
        assertThrows(IllegalArgumentException.class, () -> printer.printPlan(noType, 0));
        QueryTree noRoot = new QueryTree(QueryNodeType.SELECT, PhysicalAlgorithm.DEFAULT, null,
            List.of(new ExpressionTree(null)), List.of(scan), 0);
        assertThrows(IllegalArgumentException.class, () -> printer.printPlan(noRoot, 0));
    }

    @Test
    void entryPointWorksWithTheTreeHeldByParsedQueryAndRemainsReusableAfterErrors() {
        ParsedQuery query = new ParsedQuery("SELECT * FROM a", select("SELECT * FROM a"), List.of("a"));
        assertThrows(IllegalArgumentException.class, () -> printer.printPlan(query.getQueryTree(), -1));

        String text = printer.printPlan(query.getQueryTree(), 4);

        assertEquals("Total estimated cost: 4\nPROJECT [columns=[*]]\n  TABLE_SCAN [table=a]\n", text);
        assertEquals("SELECT * FROM a", query.rawQuery());
        assertEquals(List.of("a"), query.targetTables());
    }

    private static QueryTree scan(String table) {
        return scan(table, PhysicalAlgorithm.DEFAULT, 0);
    }

    private static QueryTree scan(String table, PhysicalAlgorithm algorithm, int cost) {
        return new QueryTree(QueryNodeType.TABLE_SCAN, algorithm, table, List.of(), List.of(), cost);
    }

    private static QueryTree select(String sql) {
        return new SelectParser(new ExpressionParser()).parseSelect(stream(sql));
    }

    private static QueryTree statement(String sql) {
        return new StatementParser(new ExpressionParser()).parseStatement(stream(sql));
    }

    private static TokenStream stream(String sql) {
        return new ShaylaLexer(sql).tokenizeToStream();
    }
}
