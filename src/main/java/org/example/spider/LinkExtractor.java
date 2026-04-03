package org.example.spider;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

public class LinkExtractor {

    public static List<String> extractLinks(String url, String htmlContent, String baseUrl) {
        List<String> links = new ArrayList<>();
        Document doc = Jsoup.parse(htmlContent, baseUrl);
        Elements elements = doc.select("a[href]");

        for (Element element : elements) {
            String absUrl = element.absUrl("href");
            if (absUrl.startsWith("http")) {
                links.add(absUrl);
            }
        }

        return links;
    }

    public static String extractTitle(String htmlContent) {
        Document doc = Jsoup.parse(htmlContent);
        return doc.title();
    }

    public static long getLastModified(org.jsoup.Connection.Response response) {
        String lastModified = response.header("Last-Modified");
        if (lastModified != null) {
            try {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US);
                return format.parse(lastModified).getTime();
            } catch (Exception e) {
                return System.currentTimeMillis();
            }
        }
        return System.currentTimeMillis();
    }
}
