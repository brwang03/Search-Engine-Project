package org.example.spider;

import org.example.spider.PageData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IndexManager {
    private final Map<String, PageData> urlToPageMap;
    private final Map<Integer, PageData> idToPageMap;
    private final String storageDir;

    public IndexManager(String storageDir) {
        this.urlToPageMap = new ConcurrentHashMap<>();
        this.idToPageMap = new ConcurrentHashMap<>();
        this.storageDir = storageDir;
        new File(storageDir).mkdirs();
    }

    public synchronized boolean shouldFetch(String url, long lastModified) {
        PageData existingPage = urlToPageMap.get(url);

        if (existingPage == null) {
            return true;
        }

        if (lastModified > existingPage.getLastModified()) {
            return true;
        }

        return false;
    }

    public synchronized PageData addOrUpdatePage(String url, int parentId, long lastModified) {
        PageData page = urlToPageMap.get(url);

        if (page == null) {
            page = new PageData(url, parentId);
            page.setLastModified(lastModified);
            urlToPageMap.put(url, page);
            idToPageMap.put(page.getPageId(), page);

            if (parentId != -1) {
                PageData parent = idToPageMap.get(parentId);
                if (parent != null) {
                    parent.addChildId(page.getPageId());
                }
            }
        } else {
            page.setLastModified(lastModified);
        }

        return page;
    }

    public void savePageContent(PageData page) throws IOException {
        String filename = storageDir + File.separator + "page_" + page.getPageId() + ".html";
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("URL: " + page.getUrl());
            writer.println("Title: " + page.getTitle());
            writer.println("Last-Modified: " + new Date(page.getLastModified()));
            writer.println("Size: " + page.getSize());
            writer.println("Parent-ID: " + page.getParentId());
            writer.println("Children-IDs: " + page.getChildrenIds());
            writer.println("\n--- Content ---\n");
            writer.println(page.getContent());
        }
    }

    // ★ 新增：获取所有已索引的页面
    public Collection<PageData> getAllPages() {
        return idToPageMap.values();
    }

    public List<Integer> getChildrenIds(int parentId) {
        PageData parent = idToPageMap.get(parentId);
        return parent != null ? parent.getChildrenIds() : new ArrayList<>();
    }

    public int getParentId(int childId) {
        PageData child = idToPageMap.get(childId);
        return child != null ? child.getParentId() : -1;
    }

    public void exportLinkStructure(String filename) throws IOException {
        String filepath = storageDir + File.separator + filename;
        try (PrintWriter writer = new PrintWriter(new FileWriter(filepath))) {
            writer.println("Page-ID | URL | Parent-ID | Children-IDs");
            writer.println("------------------------------------------------");

            for (PageData page : idToPageMap.values()) {
                writer.printf("%d | %s | %d | %s%n",
                        page.getPageId(),
                        page.getUrl(),
                        page.getParentId(),
                        page.getChildrenIds());
            }
        }
    }

    public int getTotalIndexedPages() {
        return urlToPageMap.size();
    }
}