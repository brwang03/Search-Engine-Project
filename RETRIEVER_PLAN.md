# Retrieval Function - Project Status and Planning

## Project Overview

This is a search engine project for CSIT5930 course, HKUST. The project consists of 4 main parts:
1. Spider/Crawler function (Part 1) - **COMPLETED**
2. Indexer function (Part 2) - **COMPLETED**
3. Retrieval function (Part 3) - **IN PROGRESS** (My responsibility)
4. Web interface (Part 4) - **NOT STARTED**

## Current Status (Part 3 - Retrieval Function)

### Completed by teammates:
- **Spider**: `Spider.java`, `IndexManager.java`, `LinkExtractor.java`, `PageData.java` - Fully implemented
- **Indexer**: `Indexer.java`, `InvertedIndex.java`, `StopStem.java`, `Porter.java` - Fully implemented
- **Database files**: `db/bodyIndex.db` and `db/titleIndex.db` created and populated with 300 pages indexed
- **HTML pages**: 300 pages stored in `src/main/resources/html_pages/`
- **Link structure**: `link_structure.txt` contains parent-child page relationships

### Index structure:
- **bodyIndex**: Contains terms extracted from page bodies
- **titleIndex**: Contains terms extracted from page titles
- Each posting list contains: docId, tf (term frequency), position_list
- Access via `hTree.get(term)` returns a `PostingList` object

### What I need to implement (Part 3 requirements):
1. Query processing: tokenize, remove stopwords, apply Porter stemming
2. Term weighting: `tf × idf / max(tf)` formula
3. Document similarity: cosine similarity measure
4. Phrase search support using position lists (e.g., "hong kong")
5. Title match boosting mechanism
6. Return top 50 documents ranked by score

## Implementation Plan

### Phase 1: Basic Retrieval Structure
- [ ] Create `Retriever.java` class with database connection
- [ ] Implement `SearchResult` class to hold document results
- [ ] Implement query parsing (single terms and phrase detection)

### Phase 2: TF-IDF Weighting
- [ ] Calculate document frequency (df) for each term
- [ ] Implement TF-IDF weight: `tf × idf / max(tf)`
- [ ] Calculate IDF: `log(N / df)` where N = total documents

### Phase 3: Similarity Ranking
- [ ] Build document vectors
- [ ] Build query vector
- [ ] Calculate cosine similarity
- [ ] Rank documents by score

### Phase 4: Phrase Search
- [ ] Detect phrases in query (double quotes)
- [ ] Use position lists to verify phrase proximity
- [ ] Score phrase matches appropriately

### Phase 5: Title Boosting
- [ ] Search both title and body indexes
- [ ] Apply boost factor for title matches
- [ ] Combine scores appropriately

### Phase 6: Testing
- [ ] Write test code to verify retrieval
- [ ] Verify phrase search works
- [ ] Verify ranking quality

## Task Progress

| Task | Status |
|------|--------|
| Create planning document | IN PROGRESS |
| Implement query processing | PENDING |
| Implement TF-IDF weighting | PENDING |
| Implement cosine similarity | PENDING |
| Implement phrase search | PENDING |
| Implement title boosting | PENDING |
| Return top 50 documents | PENDING |
| Test and verify | PENDING |
| Final commit | PENDING |

## Key Classes to Create/Modify

1. **`Retriever.java`** (modify existing empty class)
   - Main retrieval logic
   - Query processing
   - Document ranking

2. **New class: `SearchResult`**
   - Document ID
   - URL
   - Title
   - Score
   - Top keywords with frequencies

3. **New class: `QueryParser`**
   - Tokenize query
   - Detect phrases
   - Handle stopwords and stemming

## Database Access

```java
// Load indexes
InvertedIndex bodyIndex = new InvertedIndex("bodyIndex", "body_index");
InvertedIndex titleIndex = new InvertedIndex("titleIndex", "title_index");

// Get postings for a term
PostingList postings = (PostingList) bodyIndex.hTree.get("stemmed_term");
```

## Commit History

- `38e3c16` - fix bug
- `70d89d1` - Delete unnecessary files
- `6be0dee` - add spider source code and raw data
- `7747fa9` - Build inverted index
- `9c7f599` - Initial commit

## Last Updated

2026-04-16