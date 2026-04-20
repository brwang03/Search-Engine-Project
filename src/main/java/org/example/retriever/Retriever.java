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
    private static final double SYNONYM_TERM_WEIGHT = 0.2;
    private static final double SYNONYM_PHRASE_BOOST_MULTIPLIER = 0.3;

    public enum SimilarityMetric {
        COSINE,
        JACCARD,
        DICE
    }

    private final Map<Integer, PageInfo> pageInfoCache;
    private final Map<Integer, Double> bodyDocNorms;
    private final Map<Integer, Double> titleDocNorms;
    private SimilarityMetric similarityMetric = SimilarityMetric.COSINE;
    private final Map<String, Set<String>> synonymDictionary;
    private boolean enableSynonymExpansion = false;

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

    public static class SearchResult implements Comparable<SearchResult> {
        public final int docId;
        public final String url;
        public final String title;
        public final double score;
        public final List<Map.Entry<String, Integer>> topKeywords;
        public final int parentId;
        public final List<Integer> childrenIds;
        public final long lastModified;
        public final int size;

        public SearchResult(int docId, String url, String title, double score,
                           List<Map.Entry<String, Integer>> topKeywords, int parentId, List<Integer> childrenIds,
                           long lastModified, int size) {
            this.docId = docId;
            this.url = url;
            this.title = title;
            this.score = score;
            this.topKeywords = topKeywords;
            this.parentId = parentId;
            this.childrenIds = childrenIds;
            this.lastModified = lastModified;
            this.size = size;
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
        this.totalDocuments = 300;
        this.pageInfoCache = new HashMap<>();
        this.synonymDictionary = new HashMap<>();
        this.bodyDocNorms = buildDocumentNorms(this.bodyIndex);
        this.titleDocNorms = buildDocumentNorms(this.titleIndex);
        loadSynonyms();
        loadPageInfo();
    }

    public void setEnableSynonymExpansion(boolean enable) {
        this.enableSynonymExpansion = enable;
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
                            List<String> contentLines = Files.readAllLines(htmlFile.toPath());

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
                                } else if (cl.startsWith("Size:")) {
                                    // ★ 读取爬虫记录的原始网页���节数
                                    try {
                                        size = Integer.parseInt(cl.substring("Size:".length()).trim());
                                    } catch (NumberFormatException ignored) {}
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
            List<String> phraseTerms = tokenizePhraseWithStopwords(phrase);
            int phraseNonStopCount = 0;
            for (String term : phraseTerms) {
                if (term != null && !term.isEmpty()) {
                    phraseNonStopCount++;
                    if (!terms.contains(term)) {
                        terms.add(term);
                    }
                }
            }
            if (!phraseTerms.isEmpty() && phraseNonStopCount > 0) {
                phrases.add(phraseTerms);
                phrasePositions.add(terms.size() - phraseNonStopCount);
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

    private List<String> tokenizePhraseWithStopwords(String phrase) {
        List<String> phraseTerms = new ArrayList<>();
        String[] phraseTokens = tokenPattern.split(phrase.toLowerCase());
        for (String token : phraseTokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (stopStem.isStopWord(token)) {
                phraseTerms.add("");
                continue;
            }
            String stemmed = stopStem.stem(token);
            if (stemmed != null && !stemmed.isEmpty()) {
                phraseTerms.add(stemmed);
            } else {
                phraseTerms.add("");
            }
        }
        return phraseTerms;
    }

    private void addSynonymPhrase(String phrase, ParsedQuery query,
                                  List<String> expandedTerms,
                                  Map<String, Boolean> isSynonym) {
        List<String> phraseTerms = tokenizePhraseWithStopwords(phrase);
        if (phraseTerms.isEmpty()) {
            return;
        }
        query.phrases.add(phraseTerms);
        query.phrasePositions.add(query.terms.size());
        // Add non-stopword terms for recall, but keep them synonym-weighted.
        for (String term : phraseTerms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            if (!expandedTerms.contains(term)) {
                expandedTerms.add(term);
            }
            isSynonym.put(term, true);
        }
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
        List<String> terms = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();

        int offset = 0;
        for (String term : phraseTerms) {
            if (term == null || term.isEmpty()) {
                offset++;
                continue;
            }
            terms.add(term);
            offsets.add(offset);
            offset++;
        }

        if (terms.isEmpty()) {
            return false;
        }

        List<List<Integer>> positionsList = new ArrayList<>();
        for (String term : terms) {
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

        if (positionsList.isEmpty() || terms.size() != positionsList.size()) {
            return false;
        }

        List<Integer> basePositions = positionsList.get(0);
        int baseOffset = offsets.get(0);
        for (int i = 1; i < positionsList.size(); i++) {
            List<Integer> currentPositions = positionsList.get(i);
            int delta = offsets.get(i) - baseOffset;
            List<Integer> newBase = new ArrayList<>();
            for (int pos1 : basePositions) {
                for (int pos2 : currentPositions) {
                    if (pos2 == pos1 + delta) {
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

    private Map<Integer, Double> buildDocumentNorms(InvertedIndex index) {
        Map<Integer, Double> squaredNorms = new HashMap<>();
        try {
            jdbm.helper.FastIterator iter = index.getHTree().keys();
            String term;
            while ((term = (String) iter.next()) != null) {
                PostingList postings = (PostingList) index.getHTree().get(term);
                if (postings == null || postings.postings.isEmpty()) {
                    continue;
                }

                int maxTf = 0;
                for (Posting p : postings.postings) {
                    maxTf = Math.max(maxTf, p.freq);
                }
                if (maxTf == 0) {
                    maxTf = 1;
                }

                double idf = calculateIDF(postings.postings.size());
                for (Posting p : postings.postings) {
                    double weight = calculateTFWeight(p.freq, maxTf, idf);
                    squaredNorms.merge(p.docId, weight * weight, Double::sum);
                }
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

        Map<Integer, Double> norms = new HashMap<>();
        for (Map.Entry<Integer, Double> entry : squaredNorms.entrySet()) {
            norms.put(entry.getKey(), Math.sqrt(entry.getValue()));
        }
        return norms;
    }

    private double calculateQueryNorm(Map<String, Double> queryWeights) {
        double queryNorm = 0.0;
        for (double weight : queryWeights.values()) {
            queryNorm += weight * weight;
        }
        return Math.sqrt(queryNorm);
    }

    private double calculateCosineScore(Map<String, Double> docWeights, Map<String, Double> queryWeights,
                                        double docNorm, double queryNorm) {
        double dotProduct = calculateDotProduct(docWeights, queryWeights);

        if (docNorm == 0 || queryNorm == 0) return 0;
        return dotProduct / (docNorm * queryNorm);
    }

    private double calculateDotProduct(Map<String, Double> docWeights, Map<String, Double> queryWeights) {
        double dotProduct = 0.0;
        for (Map.Entry<String, Double> e : docWeights.entrySet()) {
            Double queryWeight = queryWeights.get(e.getKey());
            if (queryWeight != null) {
                dotProduct += e.getValue() * queryWeight;
            }
        }
        return dotProduct;
    }

    private double calculateSquaredNorm(Map<String, Double> vector) {
        double norm = 0.0;
        for (double w : vector.values()) {
            norm += w * w;
        }
        return norm;
    }

    private double calculateJaccardScore(Map<String, Double> docWeights, Map<String, Double> queryWeights) {
        // Jaccard (vector form): (D·Q) / (||D||^2 + ||Q||^2 - D·Q)
        double dot = calculateDotProduct(docWeights, queryWeights);
        double docSq = calculateSquaredNorm(docWeights);
        double querySq = calculateSquaredNorm(queryWeights);
        double denominator = docSq + querySq - dot;

        if (denominator == 0.0) return 0.0;
        return dot / denominator;
    }

    private double calculateDiceScore(Map<String, Double> docWeights, Map<String, Double> queryWeights) {
        // Dice (vector form): 2(D·Q) / (||D||^2 + ||Q||^2)
        double dot = calculateDotProduct(docWeights, queryWeights);
        double docSq = calculateSquaredNorm(docWeights);
        double querySq = calculateSquaredNorm(queryWeights);
        double denominator = docSq + querySq;

        if (denominator == 0.0) return 0.0;
        return (2.0 * dot) / denominator;
    }

    private double calculateSimilarity(Map<String, Double> docWeights, Map<String, Double> queryWeights,
                                       SimilarityMetric metric) {
        switch (metric) {
            case COSINE:
                return calculateCosineScore(docWeights, queryWeights,
                        Math.sqrt(calculateSquaredNorm(docWeights)),
                        calculateQueryNorm(queryWeights));
            case JACCARD:
                return calculateJaccardScore(docWeights, queryWeights);
            case DICE:
                return calculateDiceScore(docWeights, queryWeights);
            default:
                return 0.0;
        }
    }

    private Map<Integer, Map<String, Double>> buildDocumentVectors(List<String> terms, boolean isTitle) {
        Map<Integer, Map<String, Double>> vectors = new HashMap<>();

        for (String term : terms) {
            Map<Integer, Double> perDoc = getDocumentTFWeights(term, isTitle);
            for (Map.Entry<Integer, Double> entry : perDoc.entrySet()) {
                vectors.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                        .put(term, entry.getValue());
            }
        }

        return vectors;
    }

    private String phraseKey(List<String> phraseTerms) {
        StringBuilder sb = new StringBuilder();
        for (String term : phraseTerms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(term);
        }
        return sb.toString();
    }

    private Set<Integer> collectPhraseCandidateDocs(List<String> phraseTerms, boolean isTitle) {
        InvertedIndex index = isTitle ? titleIndex : bodyIndex;
        Set<Integer> candidates = null;
        for (String term : phraseTerms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            try {
                PostingList postings = (PostingList) index.getHTree().get(term);
                if (postings == null || postings.postings.isEmpty()) {
                    return Collections.emptySet();
                }
                Set<Integer> docIds = new HashSet<>();
                for (Posting p : postings.postings) {
                    docIds.add(p.docId);
                }
                if (candidates == null) {
                    candidates = docIds;
                } else {
                    candidates.retainAll(docIds);
                    if (candidates.isEmpty()) {
                        return Collections.emptySet();
                    }
                }
            } catch (IOException e) {
                return Collections.emptySet();
            }
        }
        return candidates == null ? Collections.emptySet() : candidates;
    }

    private double calculateDocumentScore(int docId, ParsedQuery query,
                                          Map<String, Double> queryWeights,
                                          Map<Integer, Map<String, Double>> titleVectors,
                                          Map<Integer, Map<String, Double>> bodyVectors,
                                          SimilarityMetric metric,
                                          Set<String> synonymPhraseKeys) {
        Map<String, Double> titleDocWeights = titleVectors.getOrDefault(docId, Collections.emptyMap());
        Map<String, Double> bodyDocWeights = bodyVectors.getOrDefault(docId, Collections.emptyMap());

        double score;
        if (metric == SimilarityMetric.COSINE) {
            double queryNorm = calculateQueryNorm(queryWeights);
            double titleScore = calculateCosineScore(
                    titleDocWeights,
                    queryWeights,
                    titleDocNorms.getOrDefault(docId, 0.0),
                    queryNorm
            );
            double bodyScore = calculateCosineScore(
                    bodyDocWeights,
                    queryWeights,
                    bodyDocNorms.getOrDefault(docId, 0.0),
                    queryNorm
            );
            // final score = cosine(query, body) + TITLE_BOOST * cosine(query, title)
            score = bodyScore + TITLE_BOOST * titleScore;
        } else {
            double titleScore = calculateSimilarity(titleDocWeights, queryWeights, metric);
            double bodyScore = calculateSimilarity(bodyDocWeights, queryWeights, metric);
            score = bodyScore + TITLE_BOOST * titleScore;
        }

        for (int i = 0; i < query.phrases.size(); i++) {
            List<String> phrase = query.phrases.get(i);
            boolean titleMatch = checkPhraseMatch(docId, phrase, true);
            boolean bodyMatch = checkPhraseMatch(docId, phrase, false);

            double phraseBoost = 0.0;
            if (titleMatch) phraseBoost += 0.5;
            if (bodyMatch) phraseBoost += 0.25;

            String key = phraseKey(phrase);
            if (synonymPhraseKeys.contains(key)) {
                phraseBoost *= SYNONYM_PHRASE_BOOST_MULTIPLIER;
            }

            // Phrase matches act as a multiplicative boost after similarity calculation.
            if (phraseBoost > 0) {
                score *= (1.0 + phraseBoost);
            }
        }

        return score;
    }

    private List<String> extractRawTokens(String query) {
        List<String> tokens = new ArrayList<>();
        String[] parts = tokenPattern.split(query.toLowerCase());
        for (String token : parts) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private void mergeSynonymFlag(Map<String, Boolean> isSynonym, String stem, boolean synonym) {
        Boolean existing = isSynonym.get(stem);
        if (existing == null) {
            isSynonym.put(stem, synonym);
            return;
        }
        if (!existing) {
            return; // keep original term flag when both original and synonym map to same stem
        }
        if (!synonym) {
            isSynonym.put(stem, false);
        }
    }

    public List<SearchResult> search(String queryString) {
        return search(queryString, this.similarityMetric);
    }

    public List<SearchResult> search(String queryString, SimilarityMetric metric) {
        ParsedQuery query = parseQuery(queryString);
        List<String> rawTokens = extractRawTokens(queryString);
        if (query.terms.isEmpty() && rawTokens.isEmpty()) {
            return new ArrayList<>();
        }

        // Synonym expansion on raw tokens before stemming
        List<String> expandedRawTokens = new ArrayList<>();
        Map<String, Boolean> rawIsSynonym = new HashMap<>();
        for (String token : rawTokens) {
            if (!expandedRawTokens.contains(token)) {
                expandedRawTokens.add(token);
            }
            rawIsSynonym.put(token, false);
        }

        List<String> addedSynonymTerms = new ArrayList<>();
        List<String> addedSynonymPhrases = new ArrayList<>();
        Set<String> synonymPhraseKeys = new HashSet<>();

        if (enableSynonymExpansion) {
            for (String token : rawTokens) {
                Set<String> synonyms = getSynonyms(token);
                if (!synonyms.isEmpty()) {
                    System.out.println("Synonyms for '" + token + "': " + synonyms);
                }
                for (String syn : synonyms) {
                    if (syn.contains(" ")) {
                        // Treat multi-word synonyms as quoted phrases.
                        addSynonymPhrase(syn, query, new ArrayList<String>(), new HashMap<String, Boolean>());
                        if (!addedSynonymPhrases.contains(syn)) {
                            addedSynonymPhrases.add(syn);
                        }
                        List<String> keyTerms = tokenizePhraseWithStopwords(syn);
                        synonymPhraseKeys.add(phraseKey(keyTerms));
                        continue;
                    }
                    if (!expandedRawTokens.contains(syn)) {
                        expandedRawTokens.add(syn);
                        rawIsSynonym.put(syn, true);
                        addedSynonymTerms.add(syn);
                    }
                }
            }
        }

        if (enableSynonymExpansion && (!addedSynonymTerms.isEmpty() || !addedSynonymPhrases.isEmpty())) {
            StringBuilder expandedQuery = new StringBuilder(queryString);
            for (String term : addedSynonymTerms) {
                expandedQuery.append(" ").append(term);
            }
            for (String phrase : addedSynonymPhrases) {
                expandedQuery.append(" \"").append(phrase).append("\"");
            }
            System.out.println("Expanded query: " + expandedQuery.toString());
        }

        // Stem after expansion and filter stopwords
        List<String> expandedTerms = new ArrayList<>();
        Map<String, Boolean> isSynonym = new HashMap<>();
        for (String token : expandedRawTokens) {
            if (stopStem.isStopWord(token)) {
                continue;
            }
            String stemmed = stopStem.stem(token);
            if (stemmed == null || stemmed.isEmpty()) {
                continue;
            }
            if (!expandedTerms.contains(stemmed)) {
                expandedTerms.add(stemmed);
            }
            mergeSynonymFlag(isSynonym, stemmed, rawIsSynonym.getOrDefault(token, false));
        }

        if (expandedTerms.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Integer> df = getDocumentFrequency(expandedTerms);

        Map<Integer, Map<String, Double>> titleVectors = buildDocumentVectorsWithSynonymWeighting(expandedTerms, true, isSynonym);
        Map<Integer, Map<String, Double>> bodyVectors = buildDocumentVectorsWithSynonymWeighting(expandedTerms, false, isSynonym);

        Set<Integer> candidateDocs = new HashSet<>();
        candidateDocs.addAll(titleVectors.keySet());
        candidateDocs.addAll(bodyVectors.keySet());

        for (List<String> phrase : query.phrases) {
            candidateDocs.addAll(collectPhraseCandidateDocs(phrase, true));
            candidateDocs.addAll(collectPhraseCandidateDocs(phrase, false));
        }

        Map<String, Double> queryWeights = new HashMap<>();
        for (String term : expandedTerms) {
            int docFreq = df.getOrDefault(term, 0);
            double idf = calculateIDF(docFreq);
            if (isSynonym.getOrDefault(term, false)) {
                idf = idf * SYNONYM_TERM_WEIGHT;
            }
            queryWeights.put(term, idf);
        }

        List<SearchResult> results = new ArrayList<>();
        for (int docId : candidateDocs) {
            double score = calculateDocumentScore(docId, query, queryWeights, titleVectors, bodyVectors, metric, synonymPhraseKeys);
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

    private Map<Integer, Map<String, Double>> buildDocumentVectorsWithSynonymWeighting(
            List<String> terms, boolean isTitle, Map<String, Boolean> isSynonym) {
        Map<Integer, Map<String, Double>> vectors = new HashMap<>();

        for (String term : terms) {
            Map<Integer, Double> perDoc = getDocumentTFWeights(term, isTitle);
            boolean isSyn = isSynonym.getOrDefault(term, false);
            for (Map.Entry<Integer, Double> entry : perDoc.entrySet()) {
                Map<String, Double> docVector = vectors.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
                double weight = entry.getValue();
                if (isSyn) {
                    weight = weight * SYNONYM_TERM_WEIGHT;
                }
                docVector.put(term, weight);
            }
        }

        return vectors;
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

    private void loadSynonyms() {
        try {
            String synonymsPath = "src/main/resources/synonyms.txt";
            List<String> lines = Files.readAllLines(Paths.get(synonymsPath));
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                // Each line contains a group of synonyms separated by commas
                String[] words = line.split(",");
                // Normalize all words to lowercase and trim whitespace
                List<String> normalizedWords = new ArrayList<>();
                for (String word : words) {
                    String normalized = word.trim().toLowerCase();
                    if (!normalized.isEmpty()) {
                        normalizedWords.add(normalized);
                    }
                }
                // For each word, create a mapping to all other words in the group
                for (String word : normalizedWords) {
                    Set<String> synonyms = synonymDictionary.computeIfAbsent(word, k -> new HashSet<>());
                    for (String other : normalizedWords) {
                        if (!other.equals(word)) {
                            synonyms.add(other);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to load synonyms: " + e.getMessage());
        }
    }

    private Set<String> getSynonyms(String term) {
        return synonymDictionary.getOrDefault(term, new HashSet<>());
    }

    public void setSimilarityMetric(SimilarityMetric similarityMetric) {
        this.similarityMetric = similarityMetric == null ? SimilarityMetric.COSINE : similarityMetric;
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

            // Change this one line to quickly compare ranking functions.
            retriever.setSimilarityMetric(SimilarityMetric.COSINE);

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
