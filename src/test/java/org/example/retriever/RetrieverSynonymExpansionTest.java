package org.example.retriever;

import org.example.testutil.TestIndexSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RetrieverSynonymExpansionTest {
    @BeforeAll
    static void ensureIndex() {
        TestIndexSupport.ensureIndexAvailable();
    }

    @Test
    void synonymsDictionary_loadsAndContainsExpectedExamples() throws Exception {
        System.out.println("[RetrieverSynonymExpansionTest] Creating Retriever");
        Retriever retriever = new Retriever("src/main/resources/stopwords.txt", "bodyIndex", "titleIndex");

        Method m = Retriever.class.getDeclaredMethod("getSynonyms", String.class);
        m.setAccessible(true);
        Set<?> synonyms = (Set<?>) m.invoke(retriever, "car");
        System.out.println("[RetrieverSynonymExpansionTest] getSynonyms('car') size=" + (synonyms == null ? 0 : synonyms.size()));

        assertNotNull(synonyms);
        assertTrue(synonyms.contains("automobile"));
        assertTrue(synonyms.contains("auto"));
    }

    @Test
    void search_printsExpansionOnlyWhenEnabled() throws Exception {
        System.out.println("[RetrieverSynonymExpansionTest] Creating Retriever");
        Retriever retriever = new Retriever("src/main/resources/stopwords.txt", "bodyIndex", "titleIndex");
        PrintStream oldOut = System.out;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            oldOut.println("[RetrieverSynonymExpansionTest] Capturing System.out to verify expansion logs");
            System.setOut(new PrintStream(baos));

            retriever.setEnableSynonymExpansion(false);
            retriever.search("car");
            String noSyn = baos.toString("UTF-8");

            baos.reset();
            retriever.setEnableSynonymExpansion(true);
            retriever.search("car");
            String withSyn = baos.toString("UTF-8");

            oldOut.println("[RetrieverSynonymExpansionTest] captured bytes: noSyn=" + noSyn.length() + ", withSyn=" + withSyn.length());
            assertFalse(noSyn.contains("Expanded query:"));
            assertTrue(withSyn.contains("Expanded query:"));
            assertTrue(withSyn.contains("automobile"));
        } finally {
            System.setOut(oldOut);
        }
    }
}
