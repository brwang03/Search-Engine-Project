.PHONY: help build classpath test cli server index reindex clean

MVNW := ./scripts/mvnw
JAVA := java
CP_FILE := target/classpath.txt
CLASSPATH := target/classes:$(shell cat $(CP_FILE) 2>/dev/null)
CLI_ARGS :=

help:
	@printf "%s\n" "make build      - compile (skip tests) and generate classpath" \
		"make test       - run mvn test" \
		"make cli        - run CLI retriever (set CLI_ARGS=...)" \
		"make server     - start SearchServer on http://localhost:8080/" \
		"make index      - run Indexer (does not delete db/)" \
		"make reindex    - rm -rf db/ then run Indexer" \
		"make clean      - mvn clean"

define maybe_use_local_jdk
JDK_DIR="$$(ls -d .jdk/jdk-17* 2>/dev/null | head -n 1)"; \
if [ -n "$$JDK_DIR" ]; then \
  export JAVA_HOME="$$(cd "$$JDK_DIR" && pwd)"; \
  export PATH="$$JAVA_HOME/bin:$$PATH"; \
fi;
endef

build: classpath

classpath:
	@set -e; $(maybe_use_local_jdk) \
	$(MVNW) -DskipTests clean compile; \
	$(MVNW) -q dependency:build-classpath -Dmdep.outputFile=$(CP_FILE) -Dmdep.pathSeparator=:

test:
	@set -e; $(maybe_use_local_jdk) \
	$(MVNW) test

cli: classpath
	@set -e; $(maybe_use_local_jdk) \
	./scripts/run_retriver.sh $(CLI_ARGS)

server: classpath
	@set -e; $(maybe_use_local_jdk) \
	$(JAVA) -cp "$(CLASSPATH)" org.example.server.SearchServer

index: classpath
	@set -e; $(maybe_use_local_jdk) \
	$(JAVA) -cp "$(CLASSPATH)" org.example.indexer.Indexer

reindex: classpath
	@set -e; rm -rf db; mkdir -p db; $(maybe_use_local_jdk) \
	$(JAVA) -cp "$(CLASSPATH)" org.example.indexer.Indexer

clean:
	@set -e; $(maybe_use_local_jdk) \
	$(MVNW) clean
