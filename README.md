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
| `mdbms-mocks` | `mdbms-api` |
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
