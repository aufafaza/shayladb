package mdbms.optimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.ExpressionTree;
import mdbms.models.query.QueryTree;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.TableSchema;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.ColumnBinding;
import mdbms.optimizer.binder.QueryBinder;
import mdbms.optimizer.binder.QueryValidator;
import mdbms.optimizer.binder.TableBinding;
import mdbms.optimizer.exception.QueryErrorCollector;
import mdbms.optimizer.exception.QueryProcessingException;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.lexer.TokenStream;
import mdbms.optimizer.parser.ShaylaParser;

public class Main {
    private static final InMemorySchemaCatalog CATALOG = createDefaultCatalog();

    public static void main(String[] args) {
        if (args.length >= 2) {
            String mode = args[0].trim();
            String sql = args[1];
            execute(mode, sql);
            return;
        }

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("====================================================");
            System.out.println("   ShaylaDB Lexer, Parser & Binder CLI Stub");
            System.out.println("====================================================");
            printCatalogSummary();

            while (true) {
                System.out.println("\nChoose mode:");
                System.out.println("  [1] Lexer   (Output Tokens)");
                System.out.println("  [2] Parser  (Output Unbound QueryTree & ExpressionTree)");
                System.out.println("  [3] Binder  (Parse + Bind + Validate: Output Bound QueryTree & Binding Tables)");
                System.out.println("  [4] All     (Lexer + Parser + Binder & Validator)");
                System.out.println("  [5] Tables  (View or Add Table Schema Stubs)");
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
                if (choice.equals("5") || choice.equalsIgnoreCase("tables")) {
                    handleTableMenu(scanner);
                    continue;
                }
                if (!choice.equals("1") && !choice.equals("2") && !choice.equals("3") && !choice.equals("4")
                    && !choice.equalsIgnoreCase("lexer")
                    && !choice.equalsIgnoreCase("parser")
                    && !choice.equalsIgnoreCase("binder")
                    && !choice.equalsIgnoreCase("all")
                    && !choice.equalsIgnoreCase("both")) {
                    System.out.println("Invalid choice. Please enter 1, 2, 3, 4, 5, or 0.");
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
            || mode.equals("4") || mode.equalsIgnoreCase("all") || mode.equalsIgnoreCase("both");
        boolean runParser = mode.equals("2") || mode.equalsIgnoreCase("parser")
            || mode.equals("4") || mode.equalsIgnoreCase("all") || mode.equalsIgnoreCase("both");
        boolean runBinder = mode.equals("3") || mode.equalsIgnoreCase("binder")
            || mode.equals("4") || mode.equalsIgnoreCase("all");

        if (runLexer) {
            runLexerMode(sql);
        }
        if (runParser) {
            runParserMode(sql);
        }
        if (runBinder) {
            runBinderMode(sql);
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
        System.out.println("\n--- [PARSER OUTPUT (UNBOUND)] ---");
        QueryErrorCollector collector = new QueryErrorCollector();
        ShaylaLexer lexer = new ShaylaLexer(sql, collector);
        ShaylaParser parser = new ShaylaParser(lexer);
        try {
            QueryTree queryTree = parser.parseToTree(sql);
            System.out.println("=== Unbound QueryTree ===");
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

    public static void runBinderMode(String sql) {
        System.out.println("\n--- [BINDER & VALIDATOR OUTPUT] ---");
        QueryErrorCollector collector = new QueryErrorCollector();
        ShaylaLexer lexer = new ShaylaLexer(sql, collector);
        ShaylaParser parser = new ShaylaParser(lexer);
        try {
            QueryTree queryTree = parser.parseToTree(sql);
            System.out.println("=== QueryTree (Before Binding) ===");
            parser.printQueryTree(queryTree);

            QueryBinder binder = new QueryBinder(CATALOG, collector);
            BindingContext bindingContext = binder.bind(queryTree);

            QueryValidator validator = new QueryValidator(binder, collector);
            validator.validate(queryTree, bindingContext);

            System.out.println("\n=== QueryTree (After Binding: * Expanded & Natural Joins Resolved) ===");
            parser.printQueryTree(queryTree);

            printBindingContext(bindingContext);
            System.out.println("\n[VALIDATOR] Semantic & Type Validation PASSED.");
        } catch (QueryProcessingException e) {
            if (collector.hasError()) {
                collector.printAllExpections();
            } else {
                System.err.println(e.getFormattedError());
            }
        }
    }

    public static void printBindingContext(BindingContext context) {
        System.out.println("\n=== TableBindings ===");
        Map<String, TableBinding> tables = context.getTableBindings();
        if (tables.isEmpty()) {
            System.out.println("  (no tables bound)");
        } else {
            for (Map.Entry<String, TableBinding> entry : tables.entrySet()) {
                TableBinding tb = entry.getValue();
                List<String> cols = tb.getSchema().columns().stream()
                    .map(c -> c.name() + ":" + c.type() + (c.nullable() ? "(nullable)" : "(not null)"))
                    .toList();
                System.out.println("  - " + entry.getKey() + " -> table=" + tb.getTableName()
                    + ", alias=" + tb.getAlias()
                    + ", primaryKey=" + tb.getSchema().primaryKey()
                    + ", columns=" + cols);
            }
        }

        System.out.println("\n=== ColumnBindings ===");
        Map<ColumnReference, ColumnBinding> columns = context.getColumnBindings();
        if (columns.isEmpty()) {
            System.out.println("  (no columns bound)");
        } else {
            for (Map.Entry<ColumnReference, ColumnBinding> entry : columns.entrySet()) {
                ColumnReference ref = entry.getKey();
                ColumnBinding cb = entry.getValue();
                System.out.println("  - " + ref + " -> " + cb.getTableName() + "." + cb.getColumnName()
                    + " [type=" + cb.getDataType()
                    + ", length=" + cb.getColumn().length()
                    + ", nullable=" + cb.getColumn().nullable() + "]");
            }
        }
    }

    private static void handleTableMenu(Scanner scanner) {
        printCatalogSummary();
        System.out.print("\nAdd a new table stub? (y/n): ");
        if (!scanner.hasNextLine()) {
            return;
        }
        String answer = scanner.nextLine().trim();
        if (!answer.equalsIgnoreCase("y") && !answer.equalsIgnoreCase("yes")) {
            return;
        }
        System.out.print("Enter table name: ");
        if (!scanner.hasNextLine()) {
            return;
        }
        String tableName = scanner.nextLine().trim();
        if (tableName.isEmpty()) {
            System.out.println("Table name cannot be empty.");
            return;
        }
        System.out.println("Enter columns as comma-separated name:TYPE (types: INTEGER, FLOAT, VARCHAR, CHAR)");
        System.out.print("Example: id:INTEGER, nama:VARCHAR, nilai:FLOAT\nColumns: ");
        if (!scanner.hasNextLine()) {
            return;
        }
        String colsInput = scanner.nextLine().trim();
        if (colsInput.isEmpty()) {
            System.out.println("Columns cannot be empty.");
            return;
        }
        List<ColumnDefinition> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        for (String part : colsInput.split(",")) {
            String[] tokens = part.trim().split(":");
            String colName = tokens[0].trim();
            DataType type = DataType.VARCHAR;
            if (tokens.length > 1) {
                try {
                    type = DataType.valueOf(tokens[1].trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    System.out.println("Unknown type '" + tokens[1].trim() + "', defaulting to VARCHAR.");
                }
            }
            int size = (type == DataType.VARCHAR || type == DataType.CHAR) ? 50 : 4;
            if (columns.isEmpty()) {
                primaryKeys.add(colName);
            }
            columns.add(new ColumnDefinition(colName, type, size, true));
        }
        CATALOG.addTable(new TableSchema(tableName, columns, primaryKeys, List.of()));
        System.out.println("Added table stub '" + tableName + "' with " + columns.size() + " column(s).");
        printCatalogSummary();
    }

    private static void printCatalogSummary() {
        System.out.println("\nRegistered Table Stubs in Catalog:");
        for (TableSchema schema : CATALOG.getAllSchemas()) {
            List<String> cols = schema.columns().stream()
                .map(c -> c.name() + ":" + c.type())
                .toList();
            System.out.println("  * " + schema.tableName() + " " + cols);
        }
    }

    private static InMemorySchemaCatalog createDefaultCatalog() {
        InMemorySchemaCatalog catalog = new InMemorySchemaCatalog();

        catalog.addTable(new TableSchema(
            "siswa",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 4, false),
                new ColumnDefinition("nama", DataType.VARCHAR, 50, false),
                new ColumnDefinition("umur", DataType.INTEGER, 4, false),
                new ColumnDefinition("aktif", DataType.INTEGER, 4, true)
            ),
            List.of("id"),
            List.of()
        ));

        catalog.addTable(new TableSchema(
            "pegawai",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 4, false),
                new ColumnDefinition("nama", DataType.VARCHAR, 50, false),
                new ColumnDefinition("umur", DataType.INTEGER, 4, false),
                new ColumnDefinition("salary", DataType.FLOAT, 8, false),
                new ColumnDefinition("bonus", DataType.FLOAT, 8, true)
            ),
            List.of("id"),
            List.of()
        ));

        catalog.addTable(new TableSchema(
            "students",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 4, false),
                new ColumnDefinition("name", DataType.VARCHAR, 50, false),
                new ColumnDefinition("gpa", DataType.FLOAT, 4, true)
            ),
            List.of("id"),
            List.of()
        ));

        catalog.addTable(new TableSchema(
            "courses",
            List.of(
                new ColumnDefinition("code", DataType.VARCHAR, 10, false),
                new ColumnDefinition("title", DataType.VARCHAR, 100, false),
                new ColumnDefinition("credits", DataType.INTEGER, 4, false)
            ),
            List.of("code"),
            List.of()
        ));

        catalog.addTable(new TableSchema(
            "takes",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 4, false),
                new ColumnDefinition("code", DataType.VARCHAR, 10, false),
                new ColumnDefinition("grade", DataType.VARCHAR, 2, true)
            ),
            List.of("id", "code"),
            List.of()
        ));

        return catalog;
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

    public static class InMemorySchemaCatalog implements ISchemaCatalog {
        private final Map<String, TableSchema> tables = new LinkedHashMap<>();

        public void addTable(TableSchema schema) {
            tables.put(schema.tableName().toLowerCase(Locale.ROOT), schema);
        }

        public List<TableSchema> getAllSchemas() {
            return List.copyOf(tables.values());
        }

        @Override
        public TableSchema getTableSchema(String table) {
            return table == null ? null : tables.get(table.toLowerCase(Locale.ROOT));
        }

        @Override
        public List<String> listTable() {
            return List.copyOf(tables.keySet());
        }

        @Override
        public void updateTableSchema(TableSchema schema) {
            if (schema != null) {
                addTable(schema);
            }
        }

        @Override
        public List<IndexInfo> getIndexes(String table) {
            return List.of();
        }

        @Override
        public IndexInfo createIndex(String name, String table, String column) {
            return null;
        }
    }
}
