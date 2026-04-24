package org.example.indexer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PostingList implements Serializable {
    private static final long serialVersionUID = -4270195202488177101L;
    public List<Posting> postings;
    private transient Map<Integer, Posting> postingByDocId;

    PostingList() {
        this.postings = new ArrayList<>();
        this.postingByDocId = new HashMap<>();
    }

    public void addPosting(int docId, int position) {
        Posting existing = getPosting(docId);
        if (existing != null) {
            existing.addPosition(position);
            return;
        }

        Posting newPosting = new Posting(docId);
        newPosting.addPosition(position);
        postings.add(newPosting);
        ensureDocIndex().put(docId, newPosting);
    }

    public Posting getPosting(int docId) {
        return ensureDocIndex().get(docId);
    }

    public int size() {
        return postings.size();
    }

    public boolean isEmpty() {
        return postings.isEmpty();
    }

    public List<Posting> allPostings() {
        return postings;
    }

    public boolean containsDoc(int docId) {
        return ensureDocIndex().containsKey(docId);
    }

    public Set<Integer> docIdsSnapshot() {
        return new HashSet<>(ensureDocIndex().keySet());
    }

    private Map<Integer, Posting> ensureDocIndex() {
        if (postingByDocId == null || postingByDocId.size() != postings.size()) {
            postingByDocId = new HashMap<>();
            for (Posting posting : postings) {
                postingByDocId.put(posting.docId, posting);
            }
        }
        return postingByDocId;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < postings.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(postings.get(i));
        }
        return sb.toString();
    }
}