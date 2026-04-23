package org.example.testutil;

import org.example.indexer.Indexer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TestIndexSupport {
    private TestIndexSupport() {}

    public static void ensureIndexAvailable() {
        Path dbDir = Paths.get("db");
        Path bodyDb = dbDir.resolve("bodyIndex.db");
        Path titleDb = dbDir.resolve("titleIndex.db");

        if (Files.exists(bodyDb) && Files.exists(titleDb)) {
            System.out.println("[TestIndexSupport] Using existing index under ./db");
            return;
        }

        System.out.println("[TestIndexSupport] Missing ./db index files; rebuilding via Indexer.main()");
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream muted = new PrintStream(sink);
            System.setOut(muted);
            System.setErr(muted);
            Indexer.main(new String[0]);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        System.out.println("[TestIndexSupport] Rebuild finished");
    }
}
