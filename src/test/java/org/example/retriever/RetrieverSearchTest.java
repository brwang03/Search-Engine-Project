package org.example.retriever;

import org.example.testutil.TestIndexSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RetrieverSearchTest {
    @BeforeAll
    static void ensureIndex() {
        TestIndexSupport.ensureIndexAvailable();
    }

    @Test
    void search_emptyQuery_returnsEmptyList() throws Exception {
        System.out.println("[RetrieverSearchTest] Creating Retriever");
        Retriever retriever = new Retriever("src/main/resources/stopwords.txt", "bodyIndex", "titleIndex");
        System.out.println("[RetrieverSearchTest] Searching empty query");
        assertTrue(retriever.search("").isEmpty());
        System.out.println("[RetrieverSearchTest] Searching whitespace query");
        assertTrue(retriever.search("   ").isEmpty());
    }

    @Test
    void search_returnsSortedResultsWithValidFields() throws Exception {
        System.out.println("[RetrieverSearchTest] Creating Retriever");
        Retriever retriever = new Retriever("src/main/resources/stopwords.txt", "bodyIndex", "titleIndex");
        retriever.setSimilarityMetric(Retriever.SimilarityMetric.DICE);

        System.out.println("[RetrieverSearchTest] Selecting a sample indexed keyword to query");
        Retriever.KeywordSlice any = retriever.getIndexedKeywords("", 0, 1);
        assertNotNull(any);
        assertNotNull(any.keywords);
        assertFalse(any.keywords.isEmpty());

        String term = any.keywords.get(0);
        System.out.println("[RetrieverSearchTest] Searching term='" + term + "'");
        List<Retriever.SearchResult> results = retriever.search(term);
        System.out.println("[RetrieverSearchTest] Got results.size=" + results.size());

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 50);

        double prevScore = Double.POSITIVE_INFINITY;
        for (Retriever.SearchResult r : results) {
            assertTrue(r.docId >= 1);
            assertNotNull(r.title);
            assertFalse(r.title.trim().isEmpty());
            assertNotNull(r.url);
            assertFalse(r.url.trim().isEmpty());
            assertTrue(r.score > 0.0);
            assertTrue(r.score <= prevScore + 1e-9);
            prevScore = r.score;

            assertNotNull(r.topKeywords);
            assertTrue(r.topKeywords.size() <= 5);
            for (Map.Entry<String, Integer> kw : r.topKeywords) {
                assertNotNull(kw.getKey());
                assertFalse(kw.getKey().trim().isEmpty());
                assertNotNull(kw.getValue());
                assertTrue(kw.getValue() > 0);
            }
        }
    }
}
