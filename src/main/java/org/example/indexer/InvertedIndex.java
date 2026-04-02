package org.example.indexer;

import jdbm.RecordManager;
import jdbm.RecordManagerFactory;
import jdbm.htree.HTree;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Posting entry: stores document ID, frequency, and position list for phrase search support
 */
class Posting implements Serializable
{
	public int docId;
	public int freq;
	public List<Integer> positions;
	
	Posting(int docId)
	{
		this.docId = docId;
		this.freq = 0;
		this.positions = new ArrayList<>();
	}
	
	public void addPosition(int position)
	{
		positions.add(position);
		freq = positions.size();
	}
	
	public String toString()
	{
		return "doc" + docId + ": tf=" + freq + " pos=" + positions.toString();
	}
}

/**
 * PostingList: A list of postings for a single term
 * Stored as a Serializable object directly in JDBM (no string parsing needed!)
 */
class PostingList implements Serializable
{
	public List<Posting> postings;  // Sorted list of postings for a term
	
	PostingList()
	{
		this.postings = new ArrayList<>();
	}
	
	/**
	 * Add or update a posting for a given docId and position
	 */
	public void addPosting(int docId, int position)
	{
		// Find existing posting for this docId
		for (Posting p : postings) {
			if (p.docId == docId) {
				p.addPosition(position);
				return;
			}
		}
		
		// If not found, create new posting
		Posting newPosting = new Posting(docId);
		newPosting.addPosition(position);
		postings.add(newPosting);
	}
	
	@Override
	public String toString()
	{
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

public class InvertedIndex
{
	private final RecordManager recordManager;
	private HTree hTree;

	public InvertedIndex(String recordManagerName, String objectName) throws IOException
	{
		// Create db directory if it doesn't exist
		String dbDir = "db";
		Path dbpath = Paths.get(dbDir);
		if (!Files.exists(dbpath)) {
			Files.createDirectory(dbpath);
		}
		
		// Set database file path to db/ folder
		String dbFilePath = dbDir + File.separator + recordManagerName;
		recordManager = RecordManagerFactory.createRecordManager(dbFilePath);
		long recordId = recordManager.getNamedObject(objectName);
			
		if (recordId != 0) {	// The object has existed, load it
			hTree = HTree.load(recordManager, recordId);
		} else {	// Create a new index (htree)
			hTree = HTree.createInstance(recordManager);
			recordManager.setNamedObject(objectName, hTree.getRecid());
		}
	}

	public void finalizeIndex() throws IOException {
		recordManager.commit();
		recordManager.close();
	}

	/**
	 * Add a term to the index with position information
	 * @param term the word/stem to index
	 * @param docId document ID
	 * @param position word position in document
	 */
	public void addEntry(String term, int docId, int position) throws IOException
	{
		PostingList postingList = (PostingList) hTree.get(term);
		if (postingList == null) {
			postingList = new PostingList();
		}
		postingList.addPosting(docId, position);
		hTree.put(term, postingList);
	}

	public void delEntry(String word) throws IOException
	{
		hTree.remove(word);
	}

	public void printAll() throws IOException
	{
		jdbm.helper.FastIterator iter = hTree.keys();
		String key;
		while ((key = (String) iter.next()) != null) {
			PostingList postingList = (PostingList) hTree.get(key);
			System.out.println(key + " -> " + postingList.toString());
		}
	}	

	/* Sample Output
	public static void main(String[] args)
	{
		try {
			InvertedIndex index = new InvertedIndex("lab1","ht1");

			index.addEntry("cat", 1, 2);
			index.addEntry("cat", 1, 6);
			index.addEntry("dog", 2, 3);
			System.out.println("First print");
			index.printAll();
			
			index.addEntry("cat", 8, 3);
			index.addEntry("dog", 2, 10);
			index.addEntry("dog", 8, 13);
			index.addEntry("dog", 10, 5);
			System.out.println("Second print");
			index.printAll();
			
			index.delEntry("dog");
			System.out.println("Third print");
			index.printAll();
			index.finalizeIndex();
		} catch (IOException ioe) {
			System.err.println(ioe.getMessage());
		}
	}
	 */
}
