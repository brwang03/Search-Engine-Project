# Search-Engine-Project
Search Engine Project, HKUST, CSIT5930, Group 2

## Prerequisites

- JDK 17 (required by `pom.xml`)
- Linux/macOS: use `./mvnw` and `./run_retriver.sh`


## Project Data Layout

- HTML corpus: `src/main/resources/html_pages/page_<docId>.html`
- Link structure: `src/main/resources/link_structure.txt`
- Stopwords: `src/main/resources/stopwords.txt`
- Inverted indexes (JDBM): `db/`
  - `db/bodyIndex.db`, `db/bodyIndex.lg`
  - `db/titleIndex.db`, `db/titleIndex.lg`

Important: retrieval reads from `db/` (the inverted index), not by scanning HTML files at query time.

## Scenario 1: Rebuild Index + Run Retrieval

Use this when:
- `db/` is missing
- you changed the HTML corpus
- you suspect docId mismatch (query returns a docId whose `page_<docId>.html` does not contain the query terms)

### 1) Build the code

```bash
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"

./mvnw -DskipTests clean compile
```

### 2) Rebuild the index from current HTML pages

This recreates `db/` from `src/main/resources/html_pages/`:

```bash
rm -rf db
mkdir -p db

./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.pathSeparator=:
java -cp "target/classes:$(cat target/classpath.txt)" org.example.indexer.Indexer
```

DocId mapping rule:
- the indexer derives `docId` from the filename `page_<docId>.html` to keep docId stable across rebuilds.

### 3) Run retrieval (interactive or one-off)

Interactive mode:

```bash
./run_retriver.sh --interactive --top 10
```

One-off query:

```bash
./run_retriver.sh --top 10 "hong kong"
./run_retriver.sh --top 10 "\"hong kong\" universities"
```

Exit interactive mode with `:q` (or `:quit` / `exit`).

## Scenario 2: Retrieval Only (Use Existing `db/`)

Use this when:
- `db/` already exists and matches the current corpus
- you only want to run/search

### 1) Sanity-check `db/` exists

```bash
ls -la db/
```

You should see `bodyIndex.db` / `titleIndex.db` (and their `.lg` files).

### 2) Run retrieval

Interactive:

```bash
./run_retriver.sh --interactive --top 10
```

One-off:

```bash
./run_retriver.sh --top 10 hong kong
```

## Debugging: Verify Returned docId Matches Page Content

If a query returns `Doc <id>` and you want to confirm it matches the HTML page:

```bash
DOCID=71
grep -inE '\b(hong|kong)\b' "src/main/resources/html_pages/page_${DOCID}.html" | head
```

If the page does not contain the query terms but the retriever still returns it, the `db/` index is out of sync with the HTML corpus. Rebuild the index (Scenario 1).
