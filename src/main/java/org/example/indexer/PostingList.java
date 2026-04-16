package org.example.indexer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class PostingList implements Serializable {
    private static final long serialVersionUID = -4270195202488177101L;
    public List<Posting> postings;

    PostingList() {
        this.postings = new ArrayList<>();
    }

    public void addPosting(int docId, int position) {
        for (Posting p : postings) {
            if (p.docId == docId) {
                p.addPosition(position);
                return;
            }
        }
        Posting newPosting = new Posting(docId);
        newPosting.addPosition(position);
        postings.add(newPosting);
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