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

## Run

```bash
java -jar mdbms-app/target/mdbms.jar
```

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
