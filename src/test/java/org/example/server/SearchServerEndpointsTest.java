package org.example.server;

import com.sun.net.httpserver.HttpServer;
import org.example.retriever.Retriever;
import org.example.testutil.TestIndexSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class SearchServerEndpointsTest {
    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void start() throws Exception {
        System.out.println("[SearchServerEndpointsTest] Ensuring index exists");
        TestIndexSupport.ensureIndexAvailable();
        System.out.println("[SearchServerEndpointsTest] Starting SearchServer on an ephemeral port");
        Retriever retriever = new Retriever("src/main/resources/stopwords.txt", "bodyIndex", "titleIndex");
        server = SearchServer.startServer(0, retriever);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        System.out.println("[SearchServerEndpointsTest] Server started at " + baseUrl);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            System.out.println("[SearchServerEndpointsTest] Stopping server");
            server.stop(0);
        }
    }

    @Test
    void stopwordsEndpoint_returnsText() throws Exception {
        System.out.println("[SearchServerEndpointsTest] GET /api/stopwords");
        HttpResponse r = get(baseUrl + "/api/stopwords");
        System.out.println("[SearchServerEndpointsTest] /api/stopwords status=" + r.status + " bytes=" + r.body.length());
        assertEquals(200, r.status);
        assertTrue(r.body.startsWith("a\nabout\n"));
    }

    @Test
    void keywordsEndpoint_returnsJsonAndHonorsPrefix() throws Exception {
        System.out.println("[SearchServerEndpointsTest] GET /api/keywords?prefix=hong&offset=0&limit=10");
        HttpResponse r = get(baseUrl + "/api/keywords?prefix=hong&offset=0&limit=10");
        System.out.println("[SearchServerEndpointsTest] /api/keywords status=" + r.status + " bytes=" + r.body.length());
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"prefix\": \"hong\""));
        assertTrue(r.body.contains("\"keywords\":"));
        assertTrue(r.body.contains("\"hong\""));
    }

    @Test
    void searchEndpoint_returnsDocIdsThatExistAsHtmlPages() throws Exception {
        System.out.println("[SearchServerEndpointsTest] GET /api/search?q=hong&limit=1");
        HttpResponse r = get(baseUrl + "/api/search?q=hong&limit=1");
        System.out.println("[SearchServerEndpointsTest] /api/search status=" + r.status + " bytes=" + r.body.length());
        assertEquals(200, r.status);
        Integer docId = firstInt(r.body, "\"docId\"\\s*:\\s*(\\d+)");
        System.out.println("[SearchServerEndpointsTest] First docId=" + docId);
        assertNotNull(docId);
        assertTrue(Files.exists(Paths.get("src/main/resources/html_pages/page_" + docId + ".html")));
    }

    @Test
    void staticRoot_servesIndexHtml() throws Exception {
        System.out.println("[SearchServerEndpointsTest] GET /");
        HttpResponse r = get(baseUrl + "/");
        System.out.println("[SearchServerEndpointsTest] / status=" + r.status + " bytes=" + r.body.length());
        assertEquals(200, r.status);
        assertTrue(r.body.contains("CSIT5930 Web Search Engine"));
    }

    @Test
    void searchEndpoint_acceptsSynonymsToggle() throws Exception {
        System.out.println("[SearchServerEndpointsTest] GET /api/search?q=car&limit=1&synonyms=0");
        HttpResponse r1 = get(baseUrl + "/api/search?q=car&limit=1&synonyms=0");
        System.out.println("[SearchServerEndpointsTest] GET /api/search?q=car&limit=1&synonyms=true");
        HttpResponse r2 = get(baseUrl + "/api/search?q=car&limit=1&synonyms=true");
        System.out.println("[SearchServerEndpointsTest] synonyms off status=" + r1.status + ", on status=" + r2.status);
        assertEquals(200, r1.status);
        assertEquals(200, r2.status);
        assertTrue(r1.body.contains("\"results\""));
        assertTrue(r2.body.contains("\"results\""));
    }

    private static Integer firstInt(String body, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(body);
        if (!m.find()) return null;
        return Integer.parseInt(m.group(1));
    }

    private static HttpResponse get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(is);
        conn.disconnect();
        return new HttpResponse(status, body);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) >= 0) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static class HttpResponse {
        final int status;
        final String body;

        HttpResponse(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
