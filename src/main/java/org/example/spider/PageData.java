package org.example.spider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PageData {
    private static final AtomicInteger idCounter = new AtomicInteger(0);

    private final int pageId;
    private final String url;
    private final int parentId;
    private final List<Integer> childrenIds;
    private long lastModified;
    private String content;
    private String title;
    private int size;

    public PageData(String url, int parentId) {
        this.pageId = idCounter.incrementAndGet();
        this.url = url;
        this.parentId = parentId;
        this.childrenIds = new ArrayList<>();
    }

    public int getPageId() { return pageId; }
    public String getUrl() { return url; }
    public int getParentId() { return parentId; }
    public List<Integer> getChildrenIds() { return childrenIds; }
    public void addChildId(int childId) { childrenIds.add(childId); }
    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public boolean needsUpdate(long newModifiedTime) {
        return newModifiedTime > this.lastModified;
    }
}
