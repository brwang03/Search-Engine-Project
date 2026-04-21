package org.example.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.example.retriever.Retriever;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.nio.file.Paths;

public class SearchServer {
    private static final int PORT = 8080;
    private static Retriever retriever;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    public static void main(String[] args) throws Exception {
        String stopwordsPath = "src/main/resources/stopwords.txt";
        retriever = new Retriever(stopwordsPath, "bodyIndex", "titleIndex");
        retriever.setSimilarityMetric(Retriever.SimilarityMetric.DICE);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/search", new SearchHandler());
        server.createContext("/api/keywords", new KeywordsHandler());
        server.createContext("/api/stopwords", new StopwordsHandler());
        server.createContext("/view", new ViewHandler());
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Search server started at http://localhost:" + PORT);
    }

    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

            try {
                String query = "";
                int limit = 50;
                boolean useSynonyms = false;

                String rawQuery = exchange.getRequestURI().getRawQuery();
                if (rawQuery != null) {
                    String[] pairs = rawQuery.split("&");
                    for (String pair : pairs) {
                        int idx = pair.indexOf("=");
                        if (idx > 0) {
                            String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                            String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                            switch (key) {
                                case "q":
                                    query = value;
                                    break;
                                case "limit":
                                    try {
                                        limit = Math.min(50, Math.max(1, Integer.parseInt(value)));
                                    } catch (NumberFormatException ignored) {
                                    }
                                    break;
                                case "synonyms":
                                    useSynonyms = value.equals("true") || value.equals("1");
                                    break;
                            }
                        }
                    }
                }

                retriever.setEnableSynonymExpansion(useSynonyms);

                StringBuilder json = new StringBuilder();
                json.append("{\n");
                json.append("  \"query\": \"").append(escapeJson(query)).append("\",\n");
                json.append("  \"results\": [\n");

                if (!query.isEmpty()) {
                    List<Retriever.SearchResult> results = retriever.search(query);
                    int count = Math.min(limit, results.size());

                    for (int i = 0; i < count; i++) {
                        Retriever.SearchResult r = results.get(i);
                        if (i > 0) json.append(",\n");

                        json.append("    {\n");
                        json.append("      \"docId\": ").append(r.docId).append(",\n");
                        json.append("      \"score\": ").append(String.format("%.4f", r.score)).append(",\n");
                        json.append("      \"title\": \"").append(escapeJson(r.title)).append("\",\n");
                        json.append("      \"url\": \"").append(escapeJson(r.url)).append("\",\n");
                        json.append("      \"lastModified\": \"").append(escapeJson(
                                r.lastModified > 0 ? dateFormat.format(new java.util.Date(r.lastModified)) : "Unknown"
                        )).append("\",\n");
                        json.append("      \"size\": ").append(r.size).append(",\n");

                        json.append("      \"keywords\": [\n");
                        int kwCount = 0;
                        for (Map.Entry<String, Integer> kw : r.topKeywords) {
                            if (kwCount > 0) json.append(",\n");
                            json.append("        {\"term\": \"").append(escapeJson(kw.getKey()))
                                    .append("\", \"freq\": ").append(kw.getValue()).append("}");
                            kwCount++;
                            if (kwCount >= 5) break;
                        }
                        json.append("\n      ],\n");

                        if (r.parentId != -1) {
                            String pUrl = retriever.getPageUrl(r.parentId);
                            String pTitle = retriever.getPageTitle(r.parentId);
                            json.append("      \"parent\": {\"url\": \"").append(escapeJson(pUrl))
                                    .append("\", \"title\": \"").append(escapeJson(pTitle)).append("\"},\n");
                        } else {
                            json.append("      \"parent\": null,\n");
                        }

                        json.append("      \"children\": [");
                        for (int j = 0; j < r.childrenIds.size(); j++) {
                            if (j > 0) json.append(", ");
                            int childId = r.childrenIds.get(j);
                            String cUrl = retriever.getPageUrl(childId);
                            String cTitle = retriever.getPageTitle(childId);
                            json.append("{\"url\": \"").append(escapeJson(cUrl))
                                    .append("\", \"title\": \"").append(escapeJson(cTitle)).append("\"}");
                        }
                        json.append("]\n");

                        json.append("    }");
                    }
                }

                json.append("\n  ]\n}");

                byte[] response = json.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                String errJson = "{\"error\":\"Search failed\"}";
                byte[] response = errJson.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }
    }

    static class KeywordsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

            String prefix = "";
            int offset = 0;
            int limit = 60;

            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null) {
                String[] pairs = rawQuery.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                        String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                        switch (key) {
                            case "prefix":
                                prefix = value;
                                break;
                            case "offset":
                                try {
                                    offset = Math.max(0, Integer.parseInt(value));
                                } catch (NumberFormatException ignored) {
                                }
                                break;
                            case "limit":
                                try {
                                    limit = Math.min(200, Math.max(1, Integer.parseInt(value)));
                                } catch (NumberFormatException ignored) {
                                }
                                break;
                        }
                    }
                }
            }

            Retriever.KeywordSlice slice = retriever.getIndexedKeywords(prefix, offset, limit);

            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"prefix\": \"").append(SearchServer.escapeJson(prefix)).append("\",\n");
            json.append("  \"offset\": ").append(offset).append(",\n");
            json.append("  \"limit\": ").append(limit).append(",\n");
            json.append("  \"total\": ").append(slice.total).append(",\n");
            json.append("  \"keywords\": [");
            for (int i = 0; i < slice.keywords.size(); i++) {
                if (i > 0) json.append(", ");
                json.append("\"").append(SearchServer.escapeJson(slice.keywords.get(i))).append("\"");
            }
            json.append("]\n");
            json.append("}");

            byte[] response = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }

    static class ViewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");

            String rawQuery = exchange.getRequestURI().getRawQuery();
            int docId = 1;
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    int idx = pair.indexOf("=");
                    if (idx > 0 && pair.substring(0, idx).equals("page")) {
                        try {
                            docId = Integer.parseInt(URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            String htmlPath = "src/main/resources/html_pages/page_" + docId + ".html";
            File file = new File(htmlPath);
            if (file.exists()) {
                byte[] content = Files.readAllBytes(file.toPath());
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } else {
                String notFound = "<h1>404 - Page not found</h1>";
                exchange.sendResponseHeaders(404, notFound.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            String filePath = "src/main/resources/web" + path;
            File file = new File(filePath);

            if (file.exists() && !file.isDirectory()) {
                String contentType = "text/html";
                if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "application/javascript";

                exchange.getResponseHeaders().set("Content-Type", contentType);
                byte[] content = Files.readAllBytes(file.toPath());
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } else {
                String notFound = "404 Not Found";
                exchange.sendResponseHeaders(404, notFound.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound.getBytes());
                }
            }
        }
    }

    static class StopwordsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");

            String stopwordsPath = "src/main/resources/stopwords.txt";
            byte[] content;
            try {
                content = Files.readAllBytes(Paths.get(stopwordsPath));
                exchange.sendResponseHeaders(200, content.length);
            } catch (IOException e) {
                String msg = "Failed to load stopwords";
                content = msg.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, content.length);
            }

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
