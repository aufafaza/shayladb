# mDBMS

## Requirements

- Java 17+
- Maven 3.8+
- No third-party runtime dependencies

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

## Run

```bash
java -jar mdbms-app/target/mdbms.jar
```
