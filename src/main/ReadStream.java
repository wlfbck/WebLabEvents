package main;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeMap;
import org.rosuda.JRI.RMainLoopCallbacks;
import org.rosuda.JRI.Rengine;

class TextConsole implements RMainLoopCallbacks {
    public void rWriteConsole(Rengine re, String text, int oType) {
        System.out.print(text);
    }
    
    public void rBusy(Rengine re, int which) {
        System.out.println("rBusy("+which+")");
    }
    
    public String rReadConsole(Rengine re, String prompt, int addToHistory) {
        System.out.print(prompt);
        try {
            BufferedReader br=new BufferedReader(new InputStreamReader(System.in));
            String s=br.readLine();
            return (s==null||s.length()==0)?s:s+"\n";
        } catch (Exception e) {
            System.out.println("jriReadConsole exception: "+e.getMessage());
        }
        return null;
    }
    
    public void rShowMessage(Rengine re, String message) {
        System.out.println("rShowMessage \""+message+"\"");
    }
	
    public String rChooseFile(Rengine re, int newFile) {
		FileDialog fd = new FileDialog(new Frame(), (newFile==0)?"Select a file":"Select a new file", (newFile==0)?FileDialog.LOAD:FileDialog.SAVE);
		fd.show();
		String res=null;
		if (fd.getDirectory()!=null) res=fd.getDirectory();
		if (fd.getFile()!=null) res=(res==null)?fd.getFile():(res+fd.getFile());
		return res;
    }
    
    public void   rFlushConsole (Rengine re) {
    }
	
    public void   rLoadHistory  (Rengine re, String filename) {
    }			
    
    public void   rSaveHistory  (Rengine re, String filename) {
    }			
}

public class ReadStream {

	//x >> [MAGIC] >> LÖSUNG ;)
	public static void main(String[] args) throws IOException {
		
		//Start reading tweets from file
		System.out.println("Reading Tweets....");
		
		//List to store every single tweet as String
		ArrayList<String> tweetListString = new ArrayList<String>();
		
		//List to store every single tweet as Tweet
		ArrayList<Tweet> tweetList = new ArrayList<Tweet>();
		
		//Read the file
		String fileName = Paths.get("preprocessedTweets.txt").toAbsolutePath().toString();	
		BufferedReader br = new BufferedReader(new FileReader(fileName));
        String fileLine;        
        
        //TODO ES MÜSSEN NOCH ALLE "\n" innerhalb eines Tweets entfernt werden, sonst können wir nicht Zeilenweise einlesen  
        
        while((fileLine = br.readLine()) != null) {
        	//Add a single tweet to the stringlist
        	tweetListString.add(fileLine);
        	
        	//Delete "{", "}", "\n" from the tweet
        	fileLine = fileLine.replace("{", "");
        	fileLine = fileLine.replace("}", "");
        	
        	//Split tweet at ";" and fill tweet List
        	String[] tweetData = fileLine.split(";");
        	
        	//System.out.println(fileLine);
        	//System.out.println("| 0: " + tweetData[0] + " \n| 1:" + tweetData[1] + " \n| 2: " + tweetData[2] + " \n| 3: " + tweetData[3] + "\n");

        	//Parsing string into tweet - ID
        	double tweetId = Double.parseDouble(tweetData[0]);
        	
        	//Parsing string into tweet - Date
        	DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss zzz yyyy", Locale.ENGLISH);
        	LocalDateTime tweetDate = LocalDateTime.parse(tweetData[1], formatter);

        	//Parsing string into tweet - Hashtag
        	tweetData[2] = tweetData[2].replace("[", "");
        	tweetData[2] = tweetData[2].replace("]", "");
        	String [] tweetHashtag = tweetData[2].split(",");
        	
        	//Parsing string into tweet - Text
        	String tweetText = tweetData[3];
        	
        	//Creating new tweet
        	Tweet t = new Tweet(tweetId, tweetDate, tweetHashtag, tweetText);
        	
        	//Add tweet to tweetList
        	tweetList.add(t);
        }
        //Finsihed reading, closing BufferedReader
        br.close();

        //List to store the daily sum of tweets, sorted by the day 
        TreeMap<String, Integer> dailySum = new TreeMap<String, Integer>();
        
        //Filling the dailySum list >> TODO - EVENTUELL SCHON BEIM EINLESEN DER TWEETS PRO ZEILE? 
        for(Tweet t: tweetList) {
        	String str = t.getTweetDate().getDayOfYear() + " " + t.getTweetDate().getYear();
        	if (dailySum.get(str) != null) {
        		dailySum.put(str, dailySum.get(str)+1);
        	} else {
        		dailySum.put(str, 1);
        	}
        }

        //Convert dailySum into a vector
        int[] dailySumVector = dailySum.values().stream().mapToInt(i->i).toArray();
        
        //Calling R
        Rengine re = new Rengine(args, false, new TextConsole());
        if (!re.waitForR()) {
            System.out.println("Cannot load R");
            return;
        }
        
        try {
        	re.assign("v", dailySumVector);
            System.out.println(re.eval("ts(v)"));
        	
        } catch (Exception e) {
			System.out.println("EX:"+e);
			e.printStackTrace();
		}
        
        
        
        }		
	}