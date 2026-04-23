package org.example.retriever;

import org.example.testutil.TestIndexSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RetrieverKeywordSliceTest {
    @BeforeAll
    static void ensureIndex() {
        TestIndexSupport.ensureIndexAvailable();
    }

    @Test
    void getIndexedKeywords_returnsSortedUniqueKeywords() throws Exception {
        System.out.println("[RetrieverKeywordSliceTest] Creating Retriever");
        Retriever retriever = new Retriever("src/main/resources/stopwords.txt", "bodyIndex", "titleIndex");
        System.out.println("[RetrieverKeywordSliceTest] Fetching keywords slice prefix='' offset=0 limit=50");
        Retriever.KeywordSlice slice = retriever.getIndexedKeywords("", 0, 50);
        System.out.println("[RetrieverKeywordSliceTest] slice.total=" + slice.total + " slice.keywords.size=" + slice.keywords.size());

        assertNotNull(slice);
        assertNotNull(slice.keywords);
        assertTrue(slice.total >= slice.keywords.size());
        assertFalse(slice.keywords.isEmpty());

        List<String> keywords = slice.keywords;
        for (int i = 1; i < keywords.size(); i++) {
            assertTrue(keywords.get(i - 1).compareTo(keywords.get(i)) <= 0);
        }

        Set<String> unique = new HashSet<>(keywords);
        assertEquals(unique.size(), keywords.size());
    }

    @Test
    void getIndexedKeywords_filtersByPrefix_caseInsensitive() throws Exception {
        System.out.println("[RetrieverKeywordSliceTest] Creating Retriever");
        Retriever retriever = new Retriever("src/main/resources/stopwords.txt", "bodyIndex", "titleIndex");

        System.out.println("[RetrieverKeywordSliceTest] Fetching keywords slice prefix='hong' and prefix='HONG'");
        Retriever.KeywordSlice lower = retriever.getIndexedKeywords("hong", 0, 20);
        Retriever.KeywordSlice upper = retriever.getIndexedKeywords("HONG", 0, 20);
        System.out.println("[RetrieverKeywordSliceTest] lower.total=" + lower.total + " lower.keywords.size=" + lower.keywords.size());

        assertEquals(lower.total, upper.total);
        assertEquals(lower.keywords, upper.keywords);

        for (String kw : lower.keywords) {
            assertTrue(kw.startsWith("hong"));
        }
    }
}
