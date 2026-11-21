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

## Network protocol

Programmatic clients use the framed raw-TCP protocol documented in
[`mdbms-net/PROTOCOL.md`](mdbms-net/PROTOCOL.md) on port 7654 by default.
Interactive Telnet clients connect to the separate port 2323 by default. The
ports are represented separately in `DBMSConfig`.

## Transactions

`ConcurrencyControlManager` owns the transaction lifecycle. The WAL is written through the recovery manager, and the query processor only asks the CCM to commit or abort.

| Situation | What happens |
|---|---|
| `COMMIT` | The `COMMIT` record is appended and forced to disk first. Locks are released only after that, and only then does the client get success. |
| `ABORT` | All writes are undone, the `ABORT` record is forced, and only then are locks released. |
| A statement fails (parse, type, missing table, storage error, lock conflict) | The whole transaction is aborted, including writes from earlier statements. A later `COMMIT` on it fails with `Unknown transaction`. |
| Appending the `COMMIT` record fails | `COMMIT` fails and the transaction is rolled back. The engine keeps running. |
| Forcing the `COMMIT` record fails | The outcome is uncertain, so the transaction is neither reported as committed nor rolled back. The engine moves to its failed state. |
| Rollback fails | `ABORT` fails, and the engine moves to its failed state. |

In the **failed state**, the transaction that caused it keeps its locks, so no other transaction can read or overwrite its rows. New transactions are rejected with `Engine is in failed state, restart required`, and the checkpoint barrier (`awaitNoActiveTransactions`) fails with the same error instead of waiting forever. Restart the server: crash recovery replays the WAL and decides whether the transaction committed.

Known limitation: undoing an `UPDATE` that moved a row to another page is not supported yet (#55). Aborting such a transaction moves the engine to the failed state.
