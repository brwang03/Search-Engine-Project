package org.example.indexer;

import jdbm.RecordManager;
import jdbm.RecordManagerFactory;
import jdbm.htree.HTree;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

	public HTree getHTree() {
		return hTree;
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
}
