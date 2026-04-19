package org.example.retriever;

import org.example.indexer.InvertedIndex;
import org.example.indexer.Posting;
import org.example.indexer.PostingList;
import org.example.indexer.StopStem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
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

    private final Map<Integer, PageInfo> pageInfoCache;

    private static class PageInfo {
        String url;
        String title;
        int parentId;
        List<Integer> childrenIds;
        long lastModified;
        int size;

        PageInfo(String url, String title, int parentId, List<Integer> childrenIds, long lastModified, int size) {
            this.url = url;
            this.title = title;
            this.parentId = parentId;
            this.childrenIds = childrenIds;
            this.lastModified = lastModified;
            this.size = size;
        }
    }

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

    public record SearchResult(int docId, String url, String title, double score,
                               List<Map.Entry<String, Integer>> topKeywords, int parentId, List<Integer> childrenIds,
                               long lastModified, int size) implements Comparable<SearchResult> {

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
        this.totalDocuments = 300;
        this.pageInfoCache = new HashMap<>();
        loadPageInfo();
    }

    private void loadPageInfo() {
        try {
            String linkStructurePath = "src/main/resources/link_structure.txt";
            List<String> lines = Files.readAllLines(Paths.get(linkStructurePath));

            for (int i = 2; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\|");
                if (parts.length >= 4) {
                    int docId = Integer.parseInt(parts[0].trim());
                    String url = parts[1].trim();
                    int parentId = Integer.parseInt(parts[2].trim());
                    List<Integer> children = getChildren(parts);

                    long lastModified = 0;
                    int size = 0;
                    String title = "Document " + docId;

                    try {
                        String htmlPath = "src/main/resources/html_pages/page_" + docId + ".html";
                        File htmlFile = new File(htmlPath);

                        if (htmlFile.exists()) {
                            String content = Files.readString(htmlFile.toPath());
                            size = content.length();
                            String[] contentLines = content.split("\\r?\\n");

                            for (String cl : contentLines) {
                                if (cl.startsWith("URL:")) {
                                    url = cl.substring("URL:".length()).trim();
                                } else if (cl.startsWith("Title:")) {
                                    title = cl.substring("Title:".length()).trim();
                                } else if (cl.startsWith("Last-Modified:")) {
                                    try {
                                        String dateStr = cl.substring("Last-Modified:".length()).trim();
                                        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                                                "EEE MMM dd HH:mm:ss yyyy", Locale.US);
                                        java.util.Date date = format.parse(dateStr);
                                        lastModified = date.getTime();
                                    } catch (Exception e) {
                                        lastModified = htmlFile.lastModified();
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Warning: Failed to parse HTML metadata for doc " + docId);
                        lastModified = System.currentTimeMillis();
                    }

                    pageInfoCache.put(docId, new PageInfo(url, title, parentId, children, lastModified, size));
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading page info: " + e.getMessage());
            for (int i = 1; i <= 300; i++) {
                pageInfoCache.put(i, new PageInfo("page_" + i + ".html", "Document " + i, -1, new ArrayList<>(), 0, 0));
            }
        }
    }

    private static List<Integer> getChildren(String[] parts) {
        String childrenStr = parts[3].trim();

        List<Integer> children = new ArrayList<>();
        if (!childrenStr.equals("[]")) {
            String childrenClean = childrenStr.substring(1, childrenStr.length() - 1);
            if (!childrenClean.isEmpty()) {
                String[] childArray = childrenClean.split(",");
                for (String c : childArray) {
                    children.add(Integer.parseInt(c.trim()));
                }
            }
        }
        return children;
    }

    private ParsedQuery parseQuery(String query) {
        List<String> terms = new ArrayList<>();
        List<List<String>> phrases = new ArrayList<>();
        List<Integer> phrasePositions = new ArrayList<>();

        Pattern phrasePattern = Pattern.compile("\"([^\"]+)\"");
        Matcher phraseMatcher = phrasePattern.matcher(query);

        String remaining = query;

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
                System.out.println(e.getMessage());
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
                if (maxTf == 0) maxTf = 1;
                for (Posting p : postings.postings) {
                    double idf = calculateIDF(postings.postings.size());
                    double weight = calculateTFWeight(p.freq, maxTf, idf);
                    weights.put(p.docId, weights.getOrDefault(p.docId, 0.0) + weight);
                }
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
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

    private double calculateCosineScore(Map<String, Double> docWeights, Map<String, Double> queryWeights) {
        double dotProduct = 0.0;
        double docNorm = 0.0;
        double queryNorm = 0.0;

        for (Map.Entry<String, Double> e : docWeights.entrySet()) {
            String term = e.getKey();
            double docWeight = e.getValue();
            Double queryWeight = queryWeights.get(term);
            if (queryWeight != null) {
                dotProduct += docWeight * queryWeight;
            }
            docNorm += docWeight * docWeight;
        }

        for (Double weight : queryWeights.values()) {
            queryNorm += weight * weight;
        }

        docNorm = Math.sqrt(docNorm);
        queryNorm = Math.sqrt(queryNorm);

        if (docNorm == 0 || queryNorm == 0) return 0;
        return dotProduct / (docNorm * queryNorm);
    }

    private double calculateDocumentScore(int docId, ParsedQuery query,
                                          Map<Integer, Double> titleWeights,
                                          Map<Integer, Double> bodyWeights) {
        double score;
        double titleScore = titleWeights.getOrDefault(docId, 0.0);
        double bodyScore = bodyWeights.getOrDefault(docId, 0.0);

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

        Map<String, Double> queryWeights = new HashMap<>();
        for (String term : query.terms) {
            int docFreq = df.getOrDefault(term, 0);
            double idf = calculateIDF(docFreq);
            queryWeights.put(term, idf);
        }

        List<SearchResult> results = new ArrayList<>();
        for (int docId : candidateDocs) {
            double score = calculateDocumentScore(docId, query, titleWeights, bodyWeights);
            if (score > 0) {
                PageInfo info = pageInfoCache.get(docId);
                String url = info != null ? info.url : "page_" + docId + ".html";
                String title = info != null && info.title != null ? info.title : "Document " + docId;
                int parentId = info != null ? info.parentId : -1;
                List<Integer> childrenIds = info != null ? info.childrenIds : new ArrayList<>();
                long lastModified = info != null ? info.lastModified : 0;
                int size = info != null ? info.size : 0;

                List<Map.Entry<String, Integer>> keywords = getTopKeywords(docId);

                results.add(new SearchResult(docId, url, title, score, keywords, parentId, childrenIds, lastModified, size));
            }
        }

        Collections.sort(results);
        return results.subList(0, Math.min(MAX_RESULTS, results.size()));
    }

    private List<Map.Entry<String, Integer>> getTopKeywords(int docId) {
        Map<String, Integer> termFreq = new HashMap<>();
        try {
            jdbm.helper.FastIterator iter = bodyIndex.getHTree().keys();
            String key;
            while ((key = (String) iter.next()) != null) {
                PostingList postings = (PostingList) bodyIndex.getHTree().get(key);
                if (postings != null) {
                    for (Posting p : postings.postings) {
                        if (p.docId == docId) {
                            termFreq.put(key, p.freq);
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(termFreq.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        return sorted.subList(0, Math.min(5, sorted.size()));
    }

    /**
     * Get the actual URL of a document by ID
     */
    public String getPageUrl(int docId) {
        PageInfo info = pageInfoCache.get(docId);
        return (info != null && info.url != null) ? info.url : "page_" + docId + ".html";
    }

    /**
     * Get the title of a document by ID
     */
    public String getPageTitle(int docId) {
        PageInfo info = pageInfoCache.get(docId);
        return (info != null && info.title != null) ? info.title : "Document " + docId;
    }

    private static void printResults(String query, List<SearchResult> results, int limit) {
        System.out.println("\n=== Query: " + query + " ===");
        System.out.println("Found " + results.size() + " results");
        for (int i = 0; i < Math.min(limit, results.size()); i++) {
            SearchResult r = results.get(i);
            System.out.printf("[%d] Doc %d: %s (score=%.4f)%n", i + 1, r.docId, r.title, r.score);
            System.out.printf("    URL: %s%n", r.url);
            System.out.print("    Keywords: ");
            for (Map.Entry<String, Integer> kw : r.topKeywords) {
                System.out.printf("%s(%d) ", kw.getKey(), kw.getValue());
            }
            System.out.println();
        }
    }

    private static void runInteractive(Retriever retriever, int topK) throws IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        while (true) {
            System.out.print("query> ");
            String query = reader.readLine();
            if (query == null) return;
            query = query.trim();
            if (query.isEmpty()) continue;
            if (query.equalsIgnoreCase(":q") || query.equalsIgnoreCase(":quit") || query.equalsIgnoreCase("exit")) {
                return;
            }

            List<SearchResult> results = retriever.search(query);
            printResults(query, results, topK);
        }
    }

    public static void main(String[] args) {
        try {
            String stopwordsPath = "src/main/resources/stopwords.txt";
            Retriever retriever = new Retriever(stopwordsPath, "bodyIndex", "titleIndex");

            boolean interactive = false;
            Integer topK = null;
            List<String> queryParts = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--interactive") || a.equals("-i")) {
                    interactive = true;
                } else if (a.equals("--top") && i + 1 < args.length) {
                    try {
                        topK = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException ignored) {
                        topK = null;
                    }
                } else if (a.startsWith("--top=")) {
                    try {
                        topK = Integer.parseInt(a.substring("--top=".length()));
                    } catch (NumberFormatException ignored) {
                        topK = null;
                    }
                } else if (!a.startsWith("-")) {
                    queryParts.add(a);
                }
            }

            int limit = topK != null && topK > 0 ? Math.min(topK, MAX_RESULTS) : 5;
            int interactiveLimit = topK != null && topK > 0 ? Math.min(topK, MAX_RESULTS) : 10;

            if (interactive) {
                runInteractive(retriever, interactiveLimit);
                return;
            }

            if (!queryParts.isEmpty()) {
                String query = String.join(" ", queryParts);
                List<SearchResult> results = retriever.search(query);
                printResults(query, results, limit);
                return;
            }

            String[] testQueries = {
                "hong kong",
                "\"hong kong\"",
                "university",
                "test"
            };

            for (String query : testQueries) {
                List<SearchResult> results = retriever.search(query);
                printResults(query, results, limit);
            }
        } catch (IOException e) {
            System.err.println("Error initializing retriever: " + e.getMessage());
        }
    }
}
