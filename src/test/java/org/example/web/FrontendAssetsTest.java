package org.example.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class FrontendAssetsTest {
    @Test
    void indexHtml_containsKeyUiFeatures() throws Exception {
        System.out.println("[FrontendAssetsTest] Reading src/main/resources/web/index.html");
        String html = new String(
                Files.readAllBytes(Paths.get("src/main/resources/web/index.html")),
                StandardCharsets.UTF_8
        );
        System.out.println("[FrontendAssetsTest] Loaded index.html chars=" + html.length());

        assertTrue(html.contains("Search History"));
        assertTrue(html.contains("Get similar pages"));
        assertTrue(html.contains("id=\"keywordFilter\""));
        assertTrue(html.contains("id=\"keywordList\""));
        assertTrue(html.contains("id=\"selectedKeywordChips\""));
    }
}
