package org.example.retriever;

import org.example.indexer.InvertedIndex;
import org.example.indexer.Posting;
import org.example.indexer.PostingList;
import org.example.indexer.StopStem;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Retriever {
    private final InvertedIndex bodyIndex;
    private final InvertedIndex titleIndex;
    private final StopStem stopStem;
    private final Pattern tokenPattern;
    private final int totalDocuments;
    private static final double TITLE_BOOST = 3.0;
    private static final int MAX_RESULTS = 50;

    private static class ParsedQuery {
        List<String> terms;
        List<List<String>> phrases;
        List<Integer> phrasePositions;

        ParsedQuery(List<String> terms, List<List<String>> phrases, List<Integer> phrasePositions) {
            this.terms = terms;
            this.phrases = phrases;
            this.phrasePositions = phrasePositions;
        }
    }

    public static class SearchResult implements Comparable<SearchResult> {
        public final int docId;
        public final String url;
        public final String title;
        public final double score;
        public final List<Map.Entry<String, Integer>> topKeywords;
        public final int parentId;
        public final List<Integer> childrenIds;

        public SearchResult(int docId, String url, String title, double score,
                           List<Map.Entry<String, Integer>> topKeywords,
                           int parentId, List<Integer> childrenIds) {
            this.docId = docId;
            this.url = url;
            this.title = title;
            this.score = score;
            this.topKeywords = topKeywords;
            this.parentId = parentId;
            this.childrenIds = childrenIds;
        }

        @Override
        public int compareTo(SearchResult other) {
            return Double.compare(other.score, this.score);
        }
    }

    public Retriever(String stopwordsPath, String bodyIndexDBName, String titleIndexDBName) throws IOException {
        this.stopStem = new StopStem(stopwordsPath);
        this.bodyIndex = new InvertedIndex(bodyIndexDBName, "body_index");
        this.titleIndex = new InvertedIndex(titleIndexDBName, "title_index");
        this.tokenPattern = Pattern.compile("[^a-z0-9]+");
        this.totalDocuments = countDocuments();
    }

    private int countDocuments() {
        int count = 0;
        try {
            jdbm.helper.FastIterator iter = bodyIndex.getHTree().keys();
            while (iter.next() != null) {
                count++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return Math.max(count, 300);
    }

    private ParsedQuery parseQuery(String query) {
        List<String> terms = new ArrayList<>();
        List<List<String>> phrases = new ArrayList<>();
        List<Integer> phrasePositions = new ArrayList<>();

        Pattern phrasePattern = Pattern.compile("\"([^\"]+)\"");
        Matcher phraseMatcher = phrasePattern.matcher(query);

        String remaining = query;
        int pos = 0;

        while (phraseMatcher.find()) {
            String before = remaining.substring(0, phraseMatcher.start()).trim();
            if (!before.isEmpty()) {
                String[] tokens = tokenPattern.split(before.toLowerCase());
                for (String token : tokens) {
                    if (!token.isEmpty() && !stopStem.isStopWord(token)) {
                        String stemmed = stopStem.stem(token);
                        if (stemmed != null && !stemmed.isEmpty()) {
                            terms.add(stemmed);
                        }
                    }
                }
            }

            String phrase = phraseMatcher.group(1).toLowerCase();
            List<String> phraseTerms = new ArrayList<>();
            String[] phraseTokens = tokenPattern.split(phrase);
            for (String token : phraseTokens) {
                if (!token.isEmpty() && !stopStem.isStopWord(token)) {
                    String stemmed = stopStem.stem(token);
                    if (stemmed != null && !stemmed.isEmpty()) {
                        phraseTerms.add(stemmed);
                        if (!terms.contains(stemmed)) {
                            terms.add(stemmed);
                        }
                    }
                }
            }
            if (!phraseTerms.isEmpty()) {
                phrases.add(phraseTerms);
                phrasePositions.add(terms.size() - phraseTerms.size());
            }

            remaining = remaining.substring(phraseMatcher.end());
            phraseMatcher = phrasePattern.matcher(remaining);
            pos = phraseMatcher.hitEnd() ? remaining.length() : phraseMatcher.start();
        }

        if (!remaining.trim().isEmpty()) {
            String[] tokens = tokenPattern.split(remaining.toLowerCase());
            for (String token : tokens) {
                if (!token.isEmpty() && !stopStem.isStopWord(token)) {
                    String stemmed = stopStem.stem(token);
                    if (stemmed != null && !stemmed.isEmpty() && !terms.contains(stemmed)) {
                        terms.add(stemmed);
                    }
                }
            }
        }

        return new ParsedQuery(terms, phrases, phrasePositions);
    }

    private Map<String, Integer> getDocumentFrequency(List<String> terms) {
        Map<String, Integer> df = new HashMap<>();
        for (String term : terms) {
            int count = 0;
            try {
                PostingList postings = (PostingList) bodyIndex.getHTree().get(term);
                if (postings != null) {
                    count = postings.postings.size();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            df.put(term, count);
        }
        return df;
    }

    private double calculateIDF(int df) {
        if (df == 0) return 0;
        return Math.log((double) totalDocuments / df);
    }

    private double calculateTFWeight(int tf, int maxTf, double idf) {
        if (maxTf == 0) return 0;
        return ((double) tf / maxTf) * idf;
    }

    private Map<Integer, Double> getDocumentTFWeights(String term, boolean isTitle) {
        Map<Integer, Double> weights = new HashMap<>();
        InvertedIndex index = isTitle ? titleIndex : bodyIndex;
        try {
            PostingList postings = (PostingList) index.getHTree().get(term);
            if (postings != null) {
                int maxTf = 0;
                for (Posting p : postings.postings) {
                    maxTf = Math.max(maxTf, p.freq);
                }
                for (Posting p : postings.postings) {
                    double idf = calculateIDF(postings.postings.size());
                    double weight = calculateTFWeight(p.freq, maxTf, idf);
                    weights.put(p.docId, weights.getOrDefault(p.docId, 0.0) + weight);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return weights;
    }

    private boolean checkPhraseMatch(int docId, List<String> phraseTerms, boolean isTitle) {
        InvertedIndex index = isTitle ? titleIndex : bodyIndex;
        List<List<Integer>> positionsList = new ArrayList<>();

        for (String term : phraseTerms) {
            try {
                PostingList postings = (PostingList) index.getHTree().get(term);
                if (postings == null) return false;
                Posting docPosting = null;
                for (Posting p : postings.postings) {
                    if (p.docId == docId) {
                        docPosting = p;
                        break;
                    }
                }
                if (docPosting == null) return false;
                positionsList.add(new ArrayList<>(docPosting.positions));
            } catch (IOException e) {
                return false;
            }
        }

        if (positionsList.isEmpty() || phraseTerms.size() != positionsList.size()) {
            return false;
        }

        List<Integer> basePositions = positionsList.get(0);
        for (int i = 1; i < positionsList.size(); i++) {
            List<Integer> currentPositions = positionsList.get(i);
            List<Integer> newBase = new ArrayList<>();
            for (int pos1 : basePositions) {
                for (int pos2 : currentPositions) {
                    if (pos2 == pos1 + i) {
                        newBase.add(pos2);
                        break;
                    }
                }
            }
            if (newBase.isEmpty()) return false;
            basePositions = newBase;
        }
        return true;
    }

    private double calculateDocumentScore(int docId, ParsedQuery query,
                                          Map<String, Integer> df,
                                          Map<String, Double> titleWeights,
                                          Map<String, Double> bodyWeights) {
        double score = 0.0;
        double titleScore = 0.0;
        double bodyScore = 0.0;

        for (String term : query.terms) {
            Double titleWeight = titleWeights.get(docId);
            if (titleWeight != null) {
                titleScore += titleWeight;
            }
            Double bodyWeight = bodyWeights.get(docId);
            if (bodyWeight != null) {
                bodyScore += bodyWeight;
            }
        }

        score = titleScore * TITLE_BOOST + bodyScore;

        for (int i = 0; i < query.phrases.size(); i++) {
            List<String> phrase = query.phrases.get(i);
            boolean titleMatch = checkPhraseMatch(docId, phrase, true);
            boolean bodyMatch = checkPhraseMatch(docId, phrase, false);

            double phraseBoost = 0.0;
            if (titleMatch) phraseBoost += 0.5;
            if (bodyMatch) phraseBoost += 0.25;

            score *= (1.0 + phraseBoost);
        }

        return score;
    }

    private double getQueryVectorNorm(ParsedQuery query, Map<String, Integer> df) {
        double norm = 0.0;
        for (String term : query.terms) {
            int docFreq = df.getOrDefault(term, 0);
            double idf = calculateIDF(docFreq);
            norm += idf * idf;
        }
        return Math.sqrt(norm);
    }

    public List<SearchResult> search(String queryString) {
        ParsedQuery query = parseQuery(queryString);
        if (query.terms.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Integer> df = getDocumentFrequency(query.terms);

        Map<Integer, Double> titleWeights = new HashMap<>();
        Map<Integer, Double> bodyWeights = new HashMap<>();
        Set<Integer> candidateDocs = new HashSet<>();

        for (String term : query.terms) {
            Map<Integer, Double> tw = getDocumentTFWeights(term, true);
            for (Map.Entry<Integer, Double> e : tw.entrySet()) {
                titleWeights.merge(e.getKey(), e.getValue(), Double::sum);
                candidateDocs.add(e.getKey());
            }
            Map<Integer, Double> bw = getDocumentTFWeights(term, false);
            for (Map.Entry<Integer, Double> e : bw.entrySet()) {
                bodyWeights.merge(e.getKey(), e.getValue(), Double::sum);
                candidateDocs.add(e.getKey());
            }
        }

        List<SearchResult> results = new ArrayList<>();
        for (int docId : candidateDocs) {
            double score = calculateDocumentScore(docId, query, df, titleWeights, bodyWeights);
            if (score > 0) {
                String url = getPageUrl(docId);
                String title = getPageTitle(docId);
                int parentId = getPageParentId(docId);
                List<Integer> childrenIds = getPageChildrenIds(docId);
                List<Map.Entry<String, Integer>> keywords = getTopKeywords(docId);

                results.add(new SearchResult(docId, url, title, score, keywords, parentId, childrenIds));
            }
        }

        Collections.sort(results);
        return results.subList(0, Math.min(MAX_RESULTS, results.size()));
    }

    private String getPageUrl(int docId) {
        return "page_" + docId + ".html";
    }

    private String getPageTitle(int docId) {
        return "Document " + docId;
    }

    private int getPageParentId(int docId) {
        return -1;
    }

    private List<Integer> getPageChildrenIds(int docId) {
        return new ArrayList<>();
    }

    private List<Map.Entry<String, Integer>> getTopKeywords(int docId) {
        Map<String, Integer> termFreqs = new HashMap<>();
        try {
            jdbm.helper.FastIterator iter = bodyIndex.getHTree().keys();
            String key;
            while ((key = (String) iter.next()) != null) {
                PostingList postings = (PostingList) bodyIndex.getHTree().get(key);
                if (postings != null) {
                    for (Posting p : postings.postings) {
                        if (p.docId == docId) {
                            termFreqs.put(key, p.freq);
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(termFreqs.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        return sorted.subList(0, Math.min(5, sorted.size()));
    }

    public static void main(String[] args) {
        try {
            String stopwordsPath = "src/main/resources/stopwords.txt";
            Retriever retriever = new Retriever(stopwordsPath, "bodyIndex", "titleIndex");

            String[] testQueries = {
                "hong kong",
                "\"hong kong\"",
                "university",
                "test"
            };

            for (String query : testQueries) {
                System.out.println("\n=== Query: " + query + " ===");
                List<SearchResult> results = retriever.search(query);
                System.out.println("Found " + results.size() + " results");
                for (int i = 0; i < Math.min(5, results.size()); i++) {
                    SearchResult r = results.get(i);
                    System.out.printf("[%d] Doc %d: %s (score=%.4f)%n",
                            i + 1, r.docId, r.title, r.score);
                }
            }
        } catch (IOException e) {
            System.err.println("Error initializing retriever: " + e.getMessage());
            e.printStackTrace();
        }
    }
}