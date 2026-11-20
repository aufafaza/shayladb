# mDBMS

## Requirements

- Java 17+
- Maven 3.8+
- No third-party runtime dependencies (JUnit 5 is test-only)

## Modules

| Module | Depends on |
|---|---|
| `mdbms-models` | — |
| `mdbms-api` | `mdbms-models` |
| `mdbms-storage` | `mdbms-api` |
| `mdbms-optimizer` | `mdbms-api` |
| `mdbms-processor` | `mdbms-api` |
| `mdbms-concurrency` | `mdbms-api` |
| `mdbms-recovery` | `mdbms-api` |
| `mdbms-app` | `mdbms-storage`, `mdbms-optimizer`, `mdbms-processor`, `mdbms-concurrency`, `mdbms-recovery` |

## Build

```bash
mvn clean verify                     # build everything
mvn -pl mdbms-app -am package        # build the app and its dependencies
mvn -pl mdbms-storage -am verify     # build a single module and its dependencies
```

## Test

```bash
mvn test                             # run all tests
mvn -pl mdbms-storage -am test       # run one module's tests
```

- Tests go in `mdbms-<name>/src/test/java/mdbms/<name>/`
- CI fails on a PR to `main` if any module with code has no tests, a failing test, or only skipped tests
- Modules with no classes yet and `mdbms-api` (interfaces only) don't need tests

## Schema bootstrap

There are two ways to get a usable database:

| Data directory | What happens |
|---|---|
| Empty, server started directly | The built-in sample database (SM-14) is loaded on the first start: `students`, `courses`, and `takes` with 50 rows each. |
| Initialized with `bootstrap` first | Only the tables and indexes from the script are created. The sample rows are **not** loaded, because the catalog is no longer empty. |

Tables and indexes are created offline with a schema script before the server starts. Client SQL only supports `CREATE INDEX`. `CREATE TABLE`, `DROP`, `ALTER`, and other DDL sent by a client are rejected with `Unsupported statement '...'`.

```bash
# 1. stop the server, then apply the schema to the data directory
java -jar mdbms-app/target/mdbms.jar bootstrap schema/sample.sql --data-dir data

# 2. start the server on the same data directory
java -jar mdbms-app/target/mdbms.jar
```

`--data-dir` defaults to `data`, the same directory the server uses. The command exits with `0` on success and `1` on failure.

### Script format

```sql
-- comments start with two dashes
CREATE TABLE takes (
  id INTEGER PRIMARY KEY,
  nim INTEGER NOT NULL,
  code VARCHAR(30) NOT NULL,
  FOREIGN KEY (nim) REFERENCES students(nim) ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX idx_takes_nim ON takes(nim);
```

| Supported | Notes |
|---|---|
| `CREATE TABLE` | Types: `INTEGER` (or `INT`), `FLOAT`, `CHAR(n)`, `VARCHAR(n)`. Column options: `NOT NULL`, `NULL`, `PRIMARY KEY`. |
| `PRIMARY KEY (a, b)` | Inline or table level, declared once. Primary key columns are always `NOT NULL`. |
| `FOREIGN KEY (c) REFERENCES t(c)` | The referenced table may appear later in the script. Column types must match. `ON DELETE` / `ON UPDATE`: `CASCADE`, `RESTRICT`, `SET NULL`, `NO ACTION` (default). |
| `CREATE INDEX name ON table(column)` | One column per index (B+ tree). |

Anything else (`DROP`, `ALTER`, `INSERT`, `DEFAULT`, `CHECK`, other types) is rejected with the line and column of the problem. Keywords are case-insensitive. Table, column, and index names are case-sensitive, the same as in client SQL.

### Durability policy

- Schema changes are **administrative, not transactional**. They are not written to the WAL and cannot be rolled back with `ABORT`. Run the bootstrap only while the server is stopped.
- The whole script is validated before anything is written. Duplicate names, unknown tables or columns, and mismatched foreign key types fail without touching the catalog.
- If a write fails midway, the tables and indexes created by that run are dropped again. Tables that existed before the run are left unchanged.
- The catalog is written to a temporary file, fsynced, and atomically renamed. Index pages are flushed before the command reports success, so a finished bootstrap survives a restart.
- Re-running the same script is safe. Tables and indexes that already exist with an identical definition are skipped, and a different definition under the same name is rejected. After an interrupted run, run the same script again to finish it.

## Run

```bash
java -jar mdbms-app/target/mdbms.jar
```
