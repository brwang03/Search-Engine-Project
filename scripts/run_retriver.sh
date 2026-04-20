#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

echo "===================================="
echo "Building and Running Retriever"
echo "===================================="
echo
if [[ "${1:-}" == "--interactive" || "${1:-}" == "-i" ]]; then
  echo "Interactive mode: type queries, exit with :q / :quit / exit"
fi

LOCAL_JDK="$PROJECT_DIR/.jdk/jdk-17.0.14+7"
if [[ -x "$LOCAL_JDK/bin/java" && -x "$LOCAL_JDK/bin/javac" ]]; then
  JAVA_BIN="$LOCAL_JDK/bin/java"
else
  JAVA_BIN="${JAVA_BIN:-java}"
fi

if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "Error: java not found."
  exit 1
fi

JAVA_VERSION_OUT="$("$JAVA_BIN" -version 2>&1 | head -n 1)"
if [[ "$JAVA_VERSION_OUT" != *"17."* && "$JAVA_VERSION_OUT" != *" 17"* ]]; then
  echo "Error: Java 17 is required."
  echo "Current version: $JAVA_VERSION_OUT"
  echo "Tip: install JDK 17 or place it under $PROJECT_DIR/.jdk/jdk-17.0.14+7"
  exit 1
fi

MVNW="$SCRIPT_DIR/mvnw"
if [[ ! -x "$MVNW" ]]; then
  echo "Error: $MVNW not found or not executable."
  exit 1
fi

if [[ -x "$LOCAL_JDK/bin/java" && -x "$LOCAL_JDK/bin/javac" ]]; then
  export JAVA_HOME="$LOCAL_JDK"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo
echo "Step 1: Building with Maven..."
"$MVNW" -q -DskipTests clean compile

echo
echo "Step 2: Resolving dependency classpath..."
"$MVNW" -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.pathSeparator=:

echo
echo "Step 3: Running retriever..."
"$JAVA_BIN" -cp "$PROJECT_DIR/target/classes:$(cat "$PROJECT_DIR/target/classpath.txt")" org.example.retriever.Retriever "$@"

echo
echo "===================================="
echo "Test completed"
echo "===================================="
