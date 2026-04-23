# Search-Engine-Project
Search Engine Project, HKUST, CSIT5930, Group 2

## Prerequisites

- JDK 17

## Build

```bash
./scripts/mvnw -DskipTests clean compile
./scripts/mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.pathSeparator=:
```

Or via Makefile:

```bash
make build
```

If you don't have JDK 17 on PATH but you have a local JDK under `.jdk/`:

```bash
JAVA_HOME="$PWD/.jdk/jdk-17.0.14+7" PATH="$PWD/.jdk/jdk-17.0.14+7/bin:$PATH" ./scripts/mvnw -DskipTests clean compile
JAVA_HOME="$PWD/.jdk/jdk-17.0.14+7" PATH="$PWD/.jdk/jdk-17.0.14+7/bin:$PATH" ./scripts/mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.pathSeparator=:
```

## CLI (Interactive Search)

Run:

```bash
./scripts/run_retriver.sh --interactive --top 10
```

Or:

```bash
make cli CLI_ARGS="--interactive --top 10"
```

Exit with `:q` (or `:quit` / `exit`).

## Web Page (Frontend + Backend)

Start the server:

```bash
java -cp "target/classes:$(cat target/classpath.txt)" org.example.server.SearchServer
```

Or:

```bash
make server
```

Open:

`http://localhost:8080/`

## Rebuild Index (Only If Needed)

If `db/` is missing or out of sync with `src/main/resources/html_pages/`:

```bash
rm -rf db && mkdir -p db
java -cp "target/classes:$(cat target/classpath.txt)" org.example.indexer.Indexer
```

Or:

```bash
make reindex
```
