package mdbms.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import mdbms.models.query.ExpressionTree;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QueryProcessingException;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.lexer.TokenStream;
import mdbms.optimizer.parser.ShaylaParser;

public class Main {

    public static void main(String[] args) {
        if (args.length >= 2) {
            String mode = args[0].trim();
            String sql = args[1];
            execute(mode, sql);
            return;
        }

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("==========================================");
            System.out.println("   ShaylaDB Lexer & Parser CLI Stub");
            System.out.println("==========================================");

            while (true) {
                System.out.println("\nChoose mode:");
                System.out.println("  [1] Lexer  (Output Tokens)");
                System.out.println("  [2] Parser (Output QueryTree & ExpressionTree)");
                System.out.println("  [3] Both   (Output Tokens + QueryTree & ExpressionTree)");
                System.out.println("  [0] Exit");
                System.out.print("Choice: ");

                if (!scanner.hasNextLine()) {
                    break;
                }
                String choice = scanner.nextLine().trim();
                if (choice.equals("0") || choice.equalsIgnoreCase("exit") || choice.equalsIgnoreCase("quit")) {
                    System.out.println("Exiting.");
                    break;
                }
                if (!choice.equals("1") && !choice.equals("2") && !choice.equals("3")
                    && !choice.equalsIgnoreCase("lexer")
                    && !choice.equalsIgnoreCase("parser")
                    && !choice.equalsIgnoreCase("both")) {
                    System.out.println("Invalid choice. Please enter 1, 2, 3, or 0.");
                    continue;
                }

                System.out.print("Enter SQL query: ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String sql = scanner.nextLine();
                execute(choice, sql);
            }
        }
    }

    public static void execute(String mode, String sql) {
        boolean runLexer = mode.equals("1") || mode.equalsIgnoreCase("lexer")
            || mode.equals("3") || mode.equalsIgnoreCase("both");
        boolean runParser = mode.equals("2") || mode.equalsIgnoreCase("parser")
            || mode.equals("3") || mode.equalsIgnoreCase("both");

        if (runLexer) {
            runLexerMode(sql);
        }
        if (runParser) {
            runParserMode(sql);
        }
    }

    public static void runLexerMode(String sql) {
        System.out.println("\n--- [LEXER OUTPUT] ---");
        QueryErrorCollector collector = new QueryErrorCollector();
        ShaylaLexer lexer = new ShaylaLexer(sql, collector);
        try {
            TokenStream stream = lexer.tokenizeToStream();
            stream.printAllParsedToken();
            if (collector.hasError()) {
                collector.printAllExpections();
            }
        } catch (QueryProcessingException e) {
            collector.printAllExpections();
        }
    }

    public static void runParserMode(String sql) {
        System.out.println("\n--- [PARSER OUTPUT] ---");
        QueryErrorCollector collector = new QueryErrorCollector();
        ShaylaLexer lexer = new ShaylaLexer(sql, collector);
        ShaylaParser parser = new ShaylaParser(lexer);
        try {
            QueryTree queryTree = parser.parseToTree(sql);
            System.out.println("=== QueryTree ===");
            parser.printQueryTree(queryTree);

            List<NamedExpression> expressions = new ArrayList<>();
            collectExpressions(queryTree, expressions);
            if (!expressions.isEmpty()) {
                System.out.println("\n=== ExpressionTree(s) ===");
                for (NamedExpression entry : expressions) {
                    System.out.println("[" + entry.label() + "]");
                    parser.printExpressionTree(entry.tree());
                }
            }
        } catch (QueryProcessingException e) {
            if (collector.hasError()) {
                collector.printAllExpections();
            } else {
                System.err.println(e.getFormattedError());
            }
        }
    }

    private static void collectExpressions(QueryTree node, List<NamedExpression> out) {
        if (node == null) {
            return;
        }
        List<ExpressionTree> conditions = node.getConditions();
        for (int i = 0; i < conditions.size(); i++) {
            out.add(new NamedExpression(node.getNodeType() + " condition[" + i + "]", conditions.get(i)));
        }
        if (node.getUpdateExpression() != null) {
            out.add(new NamedExpression(
                node.getNodeType() + " SET " + node.getUpdateColumn(),
                node.getUpdateExpression()
            ));
        }
        List<ExpressionTree> values = node.getInsertValues();
        for (int i = 0; i < values.size(); i++) {
            out.add(new NamedExpression(node.getNodeType() + " value[" + i + "]", values.get(i)));
        }
        for (QueryTree child : node.getChildren()) {
            collectExpressions(child, out);
        }
    }

    private record NamedExpression(String label, ExpressionTree tree) { }
}
