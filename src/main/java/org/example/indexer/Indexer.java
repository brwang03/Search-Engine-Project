package org.example.indexer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Batch HTML Indexer with support for:
 * - Two separate inverted indexes (title and body)
 * - Position information for phrase search
 * - Stop word removal and Porter stemming
 * - Vector space model
 */
public class Indexer {
    private final StopStem stopStem;
    private final InvertedIndex bodyIndex;
    private final InvertedIndex titleIndex;
    private final Pattern tokenPattern;

    private static class TermPosition {
        String term;
        int position;
        
        TermPosition(String term, int position) {
            this.term = term;
            this.position = position;
        }
    }

    public Indexer(String stopwordsPath, String bodyIndexDBName, String titleIndexDBName) throws IOException {
        this.stopStem = new StopStem(stopwordsPath);
        this.bodyIndex = new InvertedIndex(bodyIndexDBName, "body_index");
        this.titleIndex = new InvertedIndex(titleIndexDBName, "title_index");
        this.tokenPattern = Pattern.compile("[^a-z0-9]+");  // Split on non-alphanumeric
    }

    /**
     * Tokenize text, skip stop words, stemming and return list of terms with positions
     * @param text Input text
     * @return List of TermPosition objects with stemmed terms and 1-indexed positions
     */
    private List<TermPosition> tokenizeAndStemming(String text) {
        List<TermPosition> result = new ArrayList<>();
        String[] tokens = tokenPattern.split(text.toLowerCase());
        
        int position = 0;
        
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            
            // Remove stopwords
            if (stopStem.isStopWord(token)) {
                position++;
                continue;
            }
            
            // Stemming
            String stemmed = stopStem.stem(token);
            if (stemmed == null || stemmed.isEmpty()) {
                position++;
                continue;
            }
            
            result.add(new TermPosition(stemmed, position));
            position++;
        }
        
        return result;
    }

    /**
     * Index title or body from html pages
     * @param index The index (title / body)
     * @param text The HTML text (title / body) to process
     * @param docId Document ID for indexing
     */
    private void indexText(InvertedIndex index, String text, int docId) throws IOException {
        if (!text.isEmpty()) {
            List<TermPosition> terms = tokenizeAndStemming(text);

            /* Count unique terms for statistics
            Map<String, Integer> uniqueTerms = new HashMap<>();
            for (TermPosition tp : terms) {
                uniqueTerms.put(tp.term, uniqueTerms.getOrDefault(tp.term, 0) + 1);
            }
             */

            // Add all terms to index
            for (TermPosition tp : terms) {
                index.addEntry(tp.term, docId, tp.position);
            }
        }
    }

    /**
     * Process a single HTML file and index both title and body
     * @param htmlFile The HTML file to process
     * @param docId Document ID for indexing
     */
    public void processHtmlFile(File htmlFile, int docId) {
        try {
            // Parse HTML with jsoup
            Document doc = Jsoup.parse(htmlFile, "UTF-8");
            
            // Extract and process TITLE
            indexText(titleIndex, doc.select("title").text(), docId);

            // Extract and process BODY
            indexText(bodyIndex, doc.body().text(), docId);

            System.out.println("Indexed doc " + docId + ": " + htmlFile.getName());
        } catch (Exception e) {
            System.err.println("Error processing " + htmlFile.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Batch process all HTML files in a directory
     * @param htmlDirectory Path to directory containing HTML files
     */
    public void batchIndexHtmlFiles(String htmlDirectory) {
        Path dir = Paths.get(htmlDirectory);

        if (!Files.isDirectory(dir)) {
            System.err.println("Error: Directory not found: " + htmlDirectory);
            return;
        }

        int docId = 1;
        int successCount = 0;
        int errorCount = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{html,htm}")) {
            for (Path path : stream) {
                try {
                    processHtmlFile(path.toFile(), docId);
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                    System.err.println("Failed to process: " + path.getFileName());
                }
                docId++;
            }
        } catch (IOException e) {
            System.err.println("Error reading directory: " + e.getMessage());
        }

        System.out.println("\n=== Batch Indexing Complete ===");
        System.out.println("Successfully indexed: " + successCount + " files");
        System.out.println("Failed: " + errorCount + " files");
    }

    /**
     * Close both indexes and commit changes to database
     */
    public void finalizeIndex() {
        try {
            bodyIndex.finalizeIndex();
            titleIndex.finalizeIndex();
            System.out.println("Both indexes finalized and closed.");
        } catch (IOException e) {
            System.err.println("Error finalizing indexes: " + e.getMessage());
        }
    }

    /**
     * Print all indexed terms from body index
     */
    public void printBodyIndex() {
        System.out.println("\n=== Body Index ===");
        try {
            bodyIndex.printAll();
        } catch (IOException e) {
            System.err.println("Error printing body index: " + e.getMessage());
        }
    }

    /**
     * Print all indexed terms from title index
     */
    public void printTitleIndex() {
        System.out.println("\n=== Title Index ===");
        try {
            titleIndex.printAll();
        } catch (IOException e) {
            System.err.println("Error printing title index: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        String stopwordsPath = "src/main/resources/stopwords.txt";
        String htmlDirectory = "src/main/resources/html_pages";
        String bodyIndexDBName = "bodyIndex";
        String titleIndexDBName = "titleIndex";

        System.out.println("=== HTML Batch Indexer ===");

        try {
            Indexer indexer = new Indexer(stopwordsPath, bodyIndexDBName, titleIndexDBName);

            // Process all HTML files
            indexer.batchIndexHtmlFiles(htmlDirectory);

            indexer.printBodyIndex();
            indexer.printTitleIndex();

            // Finalize and close
            indexer.finalizeIndex();
        } catch (IOException e) {
            System.err.println("Fatal error: " + e.getMessage());
        }
    }
}
