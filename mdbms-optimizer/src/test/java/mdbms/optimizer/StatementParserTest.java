package mdbms.optimizer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionNode;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;

class StatementParserTest {
    private final StatementParser parser = new StatementParser(new ExpressionParser());

    @Test
    void dispatchesEveryRequiredStatementKind() {
        String[] queries = {"UPDATE a SET id = 1", "INSERT INTO a VALUES (1)", "DELETE FROM a",
            "CREATE INDEX idx ON a(id)", "BEGIN", "BEGIN TRANSACTION", "COMMIT", "ABORT"};
        QueryNodeType[] kinds = {QueryNodeType.UPDATE, QueryNodeType.INSERT, QueryNodeType.DELETE,
            QueryNodeType.CREATE_INDEX, QueryNodeType.BEGIN, QueryNodeType.BEGIN, QueryNodeType.COMMIT,
            QueryNodeType.ABORT};
        for (int i = 0; i < queries.length; i++) {
            QueryTree tree = parse(queries[i]);
            assertEquals(kinds[i], tree.getNodeType(), queries[i]);
            assertTrue(tree.getChildren().isEmpty(), queries[i]);
            assertTrue(tree.getProjectionColumns().isEmpty(), queries[i]);
            assertTrue(tree.getOrderByClauses().isEmpty(), queries[i]);
        }
    }

    @Test
    void updateRetainsTargetAssignmentAndWhereSeparately() {
        QueryTree tree = parse("UPDATE pegawai SET salary = salary * 1.05 WHERE id = 1;");

        assertEquals(QueryNodeType.UPDATE, tree.getNodeType());
        assertEquals("pegawai", tree.getTableName());
        assertEquals(new ColumnReference(null, "salary"), tree.getUpdateColumn());
        assertEquals("(* salary 1.05)", shape(tree.getUpdateExpression()));
        assertEquals(1, tree.getConditions().size());
        assertEquals("(= id 1)", shape(tree.getConditions().get(0)));
        assertTrue(tree.getInsertValues().isEmpty());
        assertNull(tree.getIndexName());
    }

    @Test
    void updateWithoutWherePreservesItsValueAndHasNoPredicate() {
        QueryTree tree = parse("UPDATE pegawai SET salary = 5000;");

        assertEquals("pegawai", tree.getTableName());
        assertEquals("5000", shape(tree.getUpdateExpression()));
        assertEquals(ExpressionNode.Type.INTEGER_LITERAL, tree.getUpdateExpression().getRoot().getType());
        assertTrue(tree.getConditions().isEmpty());
    }

    @Test
    void updatePreservesArithmeticPrecedenceAssociativityAndGrouping() {
        assertEquals("(- (- (+ salary (* bonus 2)) (/ tax 3)) 1)",
            shape(parse("UPDATE a SET salary = salary + bonus * 2 - tax / 3 - 1").getUpdateExpression()));
        assertEquals("(* (+ salary bonus) 2)",
            shape(parse("UPDATE a SET salary = (salary + bonus) * 2").getUpdateExpression()));
        assertEquals("(AND (> (* salary 1.05) 5000) (>= umur 18))",
            shape(parse("UPDATE a SET salary = 1 WHERE salary * 1.05 > 5000 AND umur >= 18")
                .getConditions().get(0)));
    }

    @Test
    void qualifiedAssignmentAndColumnOperandsPreserveIdentifierSpelling() {
        QueryTree tree = parse("uPdAtE Pegawai sEt Pegawai.Salary = Pegawai.Salary + 1 "
            + "wHeRe Pegawai.Id = otherId;");

        assertEquals("Pegawai", tree.getTableName());
        assertEquals(new ColumnReference("Pegawai", "Salary"), tree.getUpdateColumn());
        assertEquals("(+ Pegawai.Salary 1)", shape(tree.getUpdateExpression()));
        assertEquals("(= Pegawai.Id otherId)", shape(tree.getConditions().get(0)));
    }

    @Test
    void updateAcceptsTextAndRetainsItsDecodedLiteral() {
        QueryTree tree = parse("UPDATE siswa SET nama = 'it''s Ayu' WHERE nama <> ''");

        assertEquals(ExpressionNode.Type.STRING_LITERAL, tree.getUpdateExpression().getRoot().getType());
        assertEquals("it's Ayu", tree.getUpdateExpression().getRoot().getValue());
        assertEquals("<>", tree.getConditions().get(0).getRoot().getValue());
    }

    @Test
    void insertPreservesOneRowsLiteralTypesTextAndWrittenOrder() {
        QueryTree tree = parse("INSERT INTO siswa VALUES (1, 'Ayu', 18, 3.1400, '', 'it''s fine');");
        List<ExpressionTree> values = tree.getInsertValues();

        assertEquals(QueryNodeType.INSERT, tree.getNodeType());
        assertEquals("siswa", tree.getTableName());
        assertEquals(List.of("1", "Ayu", "18", "3.1400", "", "it's fine"),
            values.stream().map(v -> v.getRoot().getValue()).toList());
        assertEquals(List.of(ExpressionNode.Type.INTEGER_LITERAL, ExpressionNode.Type.STRING_LITERAL,
                ExpressionNode.Type.INTEGER_LITERAL, ExpressionNode.Type.REAL_LITERAL,
                ExpressionNode.Type.STRING_LITERAL, ExpressionNode.Type.STRING_LITERAL),
            values.stream().map(v -> v.getRoot().getType()).toList());
        assertTrue(tree.getConditions().isEmpty());
        assertNull(tree.getUpdateExpression());
        assertThrows(UnsupportedOperationException.class, () -> values.clear());
    }

    @Test
    void insertHandlesASingleValueAndLargeNumbersWithoutEvaluation() {
        String digits = "123456789012345678901234567890";

        assertEquals(digits, parse("INSERT INTO a VALUES (" + digits + ")")
            .getInsertValues().get(0).getRoot().getValue());
        assertEquals(1, parse("INSERT INTO a VALUES ('')").getInsertValues().size());
    }

    @Test
    void deleteRetainsCompleteWherePredicate() {
        QueryTree tree = parse("DELETE FROM siswa WHERE siswa.id = 1 AND (umur + 1 >= 18);");

        assertEquals(QueryNodeType.DELETE, tree.getNodeType());
        assertEquals("siswa", tree.getTableName());
        assertEquals(1, tree.getConditions().size());
        assertEquals("(AND (= siswa.id 1) (>= (+ umur 1) 18))", shape(tree.getConditions().get(0)));
    }

    @Test
    void deleteWithoutWhereHasAnEmptyPredicateForAllRows() {
        QueryTree tree = parse("DELETE FROM siswa;");

        assertEquals("siswa", tree.getTableName());
        assertTrue(tree.getConditions().isEmpty());
        assertTrue(tree.getChildren().isEmpty());
        assertTrue(tree.getInsertValues().isEmpty());
    }

    @Test
    void createIndexPreservesNameTargetAndColumn() {
        QueryTree tree = parse("CREATE INDEX idx_siswa_id ON siswa(id);");

        assertEquals(QueryNodeType.CREATE_INDEX, tree.getNodeType());
        assertEquals("idx_siswa_id", tree.getIndexName());
        assertEquals("siswa", tree.getTableName());
        assertEquals(new ColumnReference(null, "id"), tree.getIndexColumn());
        assertTrue(tree.getConditions().isEmpty());
        QueryTree mixed = parse("cReAtE iNdEx IdxSiswa oN Siswa (Id)");
        assertEquals("IdxSiswa", mixed.getIndexName());
        assertEquals("Siswa", mixed.getTableName());
        assertEquals("Id", mixed.getIndexColumn().getColumnName());
    }

    @Test
    void transactionsAreDistinctCommandsWithoutATableOrDataPayload() {
        for (String sql : List.of("BEGIN;", "BEGIN TRANSACTION;", "COMMIT;", "ABORT;", "aBoRt")) {
            QueryTree tree = parse(sql);
            assertNull(tree.getTableName());
            assertTrue(tree.getConditions().isEmpty());
            assertTrue(tree.getChildren().isEmpty());
            assertNull(tree.getUpdateColumn());
            assertNull(tree.getUpdateExpression());
            assertTrue(tree.getInsertValues().isEmpty());
            assertNull(tree.getIndexName());
            assertNull(tree.getIndexColumn());
        }
        assertEquals(QueryNodeType.BEGIN, parse("bEgIn tRaNsAcTiOn").getNodeType());
        assertEquals(QueryNodeType.COMMIT, parse("cOmMiT").getNodeType());
        assertEquals(QueryNodeType.ABORT, parse("abort").getNodeType());
    }

    @Test
    void multilineWhitespaceAndLexerCommentsAreAccepted() {
        QueryTree tree = parse("UPDATE\npegawai -- target\nSET salary =\n(salary + 1) * 2\n"
            + "WHERE id >= 1 AND umur >= 18;");

        assertEquals("(* (+ salary 1) 2)", shape(tree.getUpdateExpression()));
        assertEquals("(AND (>= id 1) (>= umur 18))", shape(tree.getConditions().get(0)));
        assertEquals(2, parse("INSERT\nINTO siswa\nVALUES (1, -- value\n'Ayu');").getInsertValues().size());
    }

    @Test
    void leavesEachSemicolonAndNextStatementForFacade() {
        TokenStream stream = stream("BEGIN; UPDATE a SET id = 1; INSERT INTO a VALUES (2); "
            + "DELETE FROM a; CREATE INDEX idx ON a(id); COMMIT; ABORT;");
        List<QueryNodeType> kinds = new ArrayList<>();

        while (!stream.isAtEnd()) {
            kinds.add(parser.parseStatement(stream).getNodeType());
            stream.expect(TokenType.semicolon);
        }

        assertEquals(List.of(QueryNodeType.BEGIN, QueryNodeType.UPDATE, QueryNodeType.INSERT,
            QueryNodeType.DELETE, QueryNodeType.CREATE_INDEX, QueryNodeType.COMMIT, QueryNodeType.ABORT), kinds);
    }

    @Test
    void acceptsEofWithoutSemicolonForEveryRequiredStatement() {
        for (String sql : List.of("UPDATE a SET id = 1", "INSERT INTO a VALUES (1)", "DELETE FROM a",
            "CREATE INDEX idx ON a(id)", "BEGIN", "BEGIN TRANSACTION", "COMMIT", "ABORT")) {
            TokenStream stream = stream(sql);
            parser.parseStatement(stream);
            assertTrue(stream.isAtEnd(), sql);
        }
    }

    @Test
    void expressionParserResultsAreRetainedDirectlyForSetAndWhere() {
        class TrackingParser extends ExpressionParser {
            final List<ExpressionTree> values = new ArrayList<>();
            final List<ExpressionTree> predicates = new ArrayList<>();

            @Override
            public ExpressionTree parseExpression(TokenStream stream) {
                ExpressionTree tree = super.parseExpression(stream);
                values.add(tree);
                return tree;
            }

            @Override
            public ExpressionTree parsePredicate(TokenStream stream) {
                ExpressionTree tree = super.parsePredicate(stream);
                predicates.add(tree);
                return tree;
            }
        }
        TrackingParser expression = new TrackingParser();
        StatementParser statements = new StatementParser(expression);
        QueryTree update = statements.parseStatement(stream("UPDATE a SET salary = salary * 1.05 WHERE id = 1"));
        QueryTree delete = statements.parseStatement(stream("DELETE FROM a WHERE id = 2"));

        assertEquals(1, expression.values.size());
        assertEquals(2, expression.predicates.size());
        assertSame(expression.values.get(0), update.getUpdateExpression());
        assertSame(expression.predicates.get(0), update.getConditions().get(0));
        assertSame(expression.predicates.get(1), delete.getConditions().get(0));
    }

    @Test
    void parserCanBeReusedAcrossFailuresAndDifferentStatementTypes() {
        for (String sql : List.of("UPDATE a", "INSERT INTO a VALUES ()", "DELETE FROM a WHERE",
            "CREATE INDEX idx", "COMMIT extra")) {
            assertThrows(QuerySyntaxException.class, () -> parse(sql));
        }
        QueryTree update = parse("UPDATE a SET id = 3");
        assertEquals("3", shape(update.getUpdateExpression()));
        assertTrue(update.getConditions().isEmpty());
        assertEquals(QueryNodeType.ABORT, parse("ABORT").getNodeType());
        assertEquals("a", parse("DELETE FROM a").getTableName());
        assertEquals("4", shape(parse("INSERT INTO a VALUES (4)").getInsertValues().get(0)));
    }

    @Test
    void rejectsUnsupportedStatementsAndTransactionAliases() {
        assertError("", "");
        assertError(";", ";");
        assertError("SELECT * FROM a", "SELECT");
        assertError("something a", "something");
        assertError("CREATE TABLE a (id INT)", "TABLE");
        assertError("ROLLBACK", "ROLLBACK");
        assertError("START TRANSACTION", "START");
    }

    @Test
    void rejectsMissingUpdatePartsAndMultipleAssignments() {
        assertError("UPDATE", "");
        assertError("UPDATE a WHERE id = 1", "WHERE");
        assertError("UPDATE a SET", "");
        assertError("UPDATE a SET = 1", "=");
        assertError("UPDATE a SET id 1", "1");
        assertError("UPDATE a SET id =", "");
        assertError("UPDATE a SET id = WHERE id = 1", "WHERE");
        assertError("UPDATE a SET id = 1, x = 2", ",");
        assertError("UPDATE a SET a. = 1", "=");
        assertError("UPDATE a SET a.* = 1", "*");
        assertError("UPDATE a SET id = 1 WHERE", "");
        assertError("UPDATE a SET id = 1 WHERE id", "id");
        assertError("UPDATE a SET id = 1 + * 2", "*");
        assertError("UPDATE a SET id = (1 + 2", "");
    }

    @Test
    void rejectsIncompleteInsertListsAndMoreThanOneRow() {
        assertError("INSERT a VALUES (1)", "a");
        assertError("INSERT INTO", "");
        assertError("INSERT INTO a (1)", "(");
        assertError("INSERT INTO a VALUES", "");
        assertError("INSERT INTO a VALUES 1", "1");
        assertError("INSERT INTO a VALUES ()", ")");
        assertError("INSERT INTO a VALUES (,1)", ",");
        assertError("INSERT INTO a VALUES (1,)", ")");
        assertError("INSERT INTO a VALUES (1 2)", "2");
        assertError("INSERT INTO a VALUES (1", "");
        assertError("INSERT INTO a VALUES (1),(2)", ",");
        assertError("INSERT INTO a VALUES (1))", ")");
    }

    @Test
    void insertRequiresLiteralValuesInsteadOfColumnReferencesOrExpressions() {
        assertError("INSERT INTO a VALUES (id)", "id");
        assertError("INSERT INTO a VALUES (other.id)", "other");
        assertError("INSERT INTO a VALUES (1 + 2)", "1");
        assertError("INSERT INTO a VALUES (NULL)", "NULL");
        assertError("INSERT INTO a VALUES (-1)", "-");
        assertError("INSERT INTO a VALUES (1 = 1)", "=");
    }

    @Test
    void rejectsMissingDeleteSourceAndIncompleteWhere() {
        assertError("DELETE a", "a");
        assertError("DELETE FROM", "");
        assertError("DELETE FROM a WHERE", "");
        assertError("DELETE FROM a WHERE id =", "");
        assertError("DELETE FROM a, b", ",");
    }

    @Test
    void createIndexRequiresAllParametersAndOneColumn() {
        assertError("CREATE", "");
        assertError("CREATE INDEX", "");
        assertError("CREATE INDEX idx", "");
        assertError("CREATE INDEX idx a(id)", "a");
        assertError("CREATE INDEX idx ON", "");
        assertError("CREATE INDEX idx ON a", "");
        assertError("CREATE INDEX idx ON a()", ")");
        assertError("CREATE INDEX idx ON a(*)", "*");
        assertError("CREATE INDEX idx ON a(id", "");
        assertError("CREATE INDEX idx ON a(id, name)", ",");
        assertError("CREATE INDEX idx ON a(a.id)", ".");
    }

    @Test
    void transactionCommandsRejectExtraArguments() {
        assertError("BEGIN extra", "extra");
        assertError("BEGIN TRANSACTION TRANSACTION", "TRANSACTION");
        assertError("COMMIT TRANSACTION", "TRANSACTION");
        assertError("ABORT TRANSACTION", "TRANSACTION");
        assertError("ABORTED", "ABORTED");
    }

    @Test
    void rejectsStrayOrOutOfOrderTokensBeforeTheTerminator() {
        assertError("UPDATE a SET id = 1 WHERE id = 1 WHERE x = 2", "WHERE");
        assertError("UPDATE a SET id = 1 ORDER BY id", "ORDER");
        assertError("UPDATE a SET id = 1 = 2", "=");
        assertError("UPDATE a SET id = (x = 1)", "(");
        assertError("INSERT INTO a VALUES (1) WHERE id = 1", "WHERE");
        assertError("DELETE FROM a SET id = 1", "SET");
        assertError("DELETE FROM a WHERE id = 1 ORDER BY id", "ORDER");
        assertError("CREATE INDEX idx ON a(id) WHERE id = 1", "WHERE");
        assertError("COMMIT)", ")");
        assertError("BEGIN COMMIT", "COMMIT");
    }

    @Test
    void multilineErrorsRetainSourceLocationAndExactlyOneCollectedException() {
        QuerySyntaxException missingOperand = assertError("UPDATE a\nSET id =\nWHERE id = 1", "WHERE");
        assertEquals(3, missingOperand.getLine());
        assertEquals(1, missingOperand.getColumn());
        QuerySyntaxException badValue = assertError("INSERT INTO a\nVALUES (1,\nname)", "name");
        assertEquals(3, badValue.getLine());
        assertEquals(1, badValue.getColumn());
        QuerySyntaxException missingIndexColumn = assertError("CREATE INDEX idx\nON a(\n)", ")");
        assertEquals(3, missingIndexColumn.getLine());
        assertEquals(1, missingIndexColumn.getColumn());
    }

    private QueryTree parse(String sql) {
        return parser.parseStatement(stream(sql));
    }

    private static TokenStream stream(String sql) {
        return new ShaylaLexer(sql).tokenizeToStream();
    }

    private static String shape(ExpressionTree tree) {
        return shape(tree.getRoot());
    }

    private static String shape(ExpressionNode node) {
        return switch (node.getType()) {
            case BINARY_OPERATION, COMPARISON, LOGICAL_AND -> "(" + node.getValue() + " "
                + shape(node.getLeft()) + " " + shape(node.getRight()) + ")";
            default -> node.getTableName() == null ? node.getValue() : node.getTableName() + "." + node.getValue();
        };
    }

    private static QuerySyntaxException assertError(String sql, String lexeme) {
        QueryErrorCollector collector = new QueryErrorCollector();
        TokenStream stream = new ShaylaLexer(sql, collector).tokenizeToStream();
        StatementParser contextual = new StatementParser(new ExpressionParser(sql, collector), sql, collector);

        QuerySyntaxException error = assertThrows(QuerySyntaxException.class,
            () -> contextual.parseStatement(stream), sql);

        assertEquals(lexeme, error.getLexeme(), sql);
        assertEquals(sql, error.getSqlSource(), sql);
        assertEquals(ErrorCategory.SYNTAX_ERROR, error.getCategory(), sql);
        assertEquals(List.of(error), collector.getAllExceptions(), sql);
        return error;
    }
}
