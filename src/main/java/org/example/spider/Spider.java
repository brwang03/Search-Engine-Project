package org.example.spider;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Spider {
    private final String startUrl;
    private final int maxPages;
    private final IndexManager indexManager;
    private final Set<String> visitedUrls;

    private final Queue<UrlEntry> urlQueue;

    private static class UrlEntry {
        String url;
        int parentId;

        UrlEntry(String url, int parentId) {
            this.url = url;
            this.parentId = parentId;
        }
    }

    public Spider(String startUrl, int maxPages, String storageDir) {
        this.startUrl = startUrl;
        this.maxPages = maxPages;
        this.indexManager = new IndexManager(storageDir);
        this.visitedUrls = ConcurrentHashMap.newKeySet();
        this.urlQueue = new LinkedList<>();
    }

    public void crawl() {
        System.out.println("Starting crawler from: " + startUrl);
        System.out.println("Target: " + maxPages + " pages");

        urlQueue.offer(new UrlEntry(startUrl, -1)); // -1表示根节点无父页面

        int processedCount = 0;

        while (!urlQueue.isEmpty() && processedCount < maxPages) {
            UrlEntry current = urlQueue.poll();
            String currentUrl = current.url;
            int parentId = current.parentId;

            if (visitedUrls.contains(currentUrl)) {
                continue;
            }

            try {
                org.jsoup.Connection.Response response = Jsoup.connect(currentUrl)
                        .timeout(10000)
                        .ignoreHttpErrors(true)
                        .execute();

                if (response.statusCode() != 200) {
                    System.out.println("Skipped (HTTP " + response.statusCode() + "): " + currentUrl);
                    continue;
                }

                byte[] rawBytes = response.bodyAsBytes();
                int actualSize = rawBytes.length;

                String charset = response.charset();
                if (charset == null || charset.isEmpty()) {
                    charset = "UTF-8";
                }
                Document doc = Jsoup.parse(new String(rawBytes, charset), currentUrl);
                String htmlContent = doc.html();
                long lastModified = LinkExtractor.getLastModified(response);

                if (!indexManager.shouldFetch(currentUrl, lastModified)) {
                    System.out.println("Skipped (not modified): " + currentUrl);
                    continue;
                }

                PageData page = indexManager.addOrUpdatePage(currentUrl, parentId, lastModified);
                page.setContent(htmlContent);
                page.setTitle(LinkExtractor.extractTitle(htmlContent));
                page.setSize(actualSize);
                indexManager.savePageContent(page);

                visitedUrls.add(currentUrl);
                processedCount++;

                System.out.printf("Crawled [%d/%d]: ID=%d, URL=%s%n",
                        processedCount, maxPages, page.getPageId(), currentUrl);

                if (processedCount < maxPages) {
                    List<String> links = LinkExtractor.extractLinks(currentUrl, htmlContent, currentUrl);

                    for (String link : links) {
                        link = link.split("#")[0];
                        if (!visitedUrls.contains(link)) {
                            urlQueue.offer(new UrlEntry(link, page.getPageId()));
                        }
                    }
                }

                Thread.sleep(100);

            } catch (IOException e) {
                System.err.println("Error fetching: " + currentUrl + " - " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // ★ 新增：爬取完成后重新保存所有页面，确保 Children-IDs 完整
        System.out.println("\nRe-saving all pages with complete child information...");
        for (PageData page : indexManager.getAllPages()) {
            try {
                indexManager.savePageContent(page);
            } catch (IOException e) {
                System.err.println("Error re-saving page " + page.getPageId() + ": " + e.getMessage());
            }
        }

        String storageDir = "src/main/resources";
        try {
            indexManager.exportLinkStructure("link_structure.txt");
            System.out.println("\nCrawling completed!");
            System.out.println("Total pages indexed: " + indexManager.getTotalIndexedPages());
            System.out.println("Link structure saved to: " + storageDir + File.separator + "link_structure.txt");
        } catch (IOException e) {
            System.err.println("Error exporting link structure: " + e.getMessage());
        }
    }

    public void printPageHierarchy(int pageId) {
        System.out.println("\n--- Page Hierarchy for ID " + pageId + " ---");

        int parentId = indexManager.getParentId(pageId);
        System.out.println("Parent ID: " + (parentId != -1 ? parentId : "None (Root)"));

        List<Integer> children = indexManager.getChildrenIds(pageId);
        System.out.println("Children IDs: " + (children.isEmpty() ? "None" : children));
    }

    public static void main(String[] args) {
        String startUrl = "https://www.cse.ust.hk/~kwtleung/COMP4321/testpage.htm";
        int maxPages = 300;
        String storageDir = "src/main/resources/html_pages";

        Spider spider = new Spider(startUrl, maxPages, storageDir);
        spider.crawl();

        for (int i = 1; i <= 5 && i <= maxPages; i++) {
            spider.printPageHierarchy(i);
        }
    }
}