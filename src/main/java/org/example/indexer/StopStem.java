package org.example.indexer;

import java.io.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;

public class StopStem
{
	private final Porter porter;
	private final HashSet<String> stopWords;
	public boolean isStopWord(String word)
	{
		return stopWords.contains(word);
	}
	public StopStem(String stopWordPath)
	{
		super();
		porter = new Porter();
		stopWords = new HashSet<>();
				
		// extract the stopwords and add them to hashset
		try {
			BufferedReader bufferedReader = new BufferedReader(new FileReader(stopWordPath));
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				if (!line.trim().isEmpty()) {
					stopWords.add(line.trim());
				}
			}
			bufferedReader.close();
		} catch (IOException e) {
			System.err.println("Error reading stopwords file: " + e.getMessage());
		}
	}
	public String stem(String str)
	{
		return porter.stripAffixes(str);
	}
	public static void main(String[] arg)
	{
		StopStem stopStem = new StopStem("src/main/resources/stopwords.txt");
		String input;
		try{
			do
			{
				System.out.print("Please enter a single English word: ");
				BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
				input = in.readLine();
				if(!input.isEmpty())
				{	
					if (stopStem.isStopWord(input))
						System.out.println("It should be stopped");
					else
			   			System.out.println("The stem of it is \"" + stopStem.stem(input)+"\"");
				}
			}
			while(!input.isEmpty());
		}
		catch(IOException ioe)
		{
			System.err.println(ioe.getMessage());
		}
	}
}
