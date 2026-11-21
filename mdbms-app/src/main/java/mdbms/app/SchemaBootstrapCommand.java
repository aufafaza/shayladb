package mdbms.app;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import mdbms.recovery.FailureRecoveryManager;
import mdbms.storage.StorageManager;
import mdbms.storage.bootstrap.SchemaBootstrap;

// perintah offline buat pasang schema dari file SQL DDL ke data directory, wajib dijalanin pas server mati
// format nya bootstrap <file.sql> [--data-dir <dir>], exit code 0 kalau sukses dan 1 kalau gagal
public final class SchemaBootstrapCommand {
    static final String NAME = "bootstrap";
    private static final int INDEX_MAX_DEGREE = 4;

    private SchemaBootstrapCommand() {}

    // jalanin perintah dari argumen CLI, args[0] nya kata bootstrap
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Path script = null;
        String dataDirectory = new DBMSConfig().getDataDirectory();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--data-dir") && i + 1 < args.length) {
                dataDirectory = args[++i];
            } else if (script == null && !args[i].startsWith("--")) {
                script = Path.of(args[i]);
            } else {
                return usage(err, "Unexpected argument: " + args[i]);
            }
        }
        if (script == null) return usage(err, "Missing schema file");

        String source;
        try {
            source = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("[ERROR] Cannot read schema file " + script + ": " + e.getMessage());
            return 1;
        }

        try {
            StorageManager storage = new StorageManager(dataDirectory, new DBMSConfig().getBufferCapacity(), INDEX_MAX_DEGREE);
            storage.attachRecoveryManager(new FailureRecoveryManager());
            SchemaBootstrap.Result result = new SchemaBootstrap(storage).apply(source);
            out.println("Schema bootstrap finished in " + Path.of(dataDirectory).toAbsolutePath());
            out.println("  created tables:  " + result.createdTables());
            out.println("  existing tables: " + result.skippedTables());
            out.println("  created indexes: " + result.createdIndexes());
            out.println("  existing indexes: " + result.skippedIndexes());
            return 0;
        } catch (RuntimeException e) {
            err.println("[ERROR] Schema bootstrap failed, nothing from this run was kept: " + e.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream err, String problem) {
        err.println("[ERROR] " + problem);
        err.println("Usage: java -jar mdbms.jar bootstrap <schema.sql> [--data-dir <dir>]");
        return 1;
    }
}
