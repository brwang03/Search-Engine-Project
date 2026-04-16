package org.example.indexer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Posting implements Serializable {
    private static final long serialVersionUID = -7578805189256116858L;
    public int docId;
    public int freq;
    public List<Integer> positions;

    Posting(int docId) {
        this.docId = docId;
        this.freq = 0;
        this.positions = new ArrayList<>();
    }

    public void addPosition(int position) {
        positions.add(position);
        freq = positions.size();
    }

    public String toString() {
        return "doc" + docId + ": tf=" + freq + " pos=" + positions.toString();
    }
}