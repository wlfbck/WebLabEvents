package main;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TreeMap;
import org.rosuda.JRI.Rengine;

/*
 * Bedingungen für einen erfolgreichen Durchlauf:
 * Keine Zeilenumbrüche in der Tweetdatei - Eine Zeile, ein Tweet!
 * Tweets liegen in zeitlich sortierter Reihenfolge vor
 */

public class ReadStream {

    public static void main(String[] args) throws IOException {
        ReadStream rs = new ReadStream();
        System.out.println("Processing Tweets");
        rs.processTweets();
        System.out.println("Done, exiting");
        rs.exit();
    }

    //List to store every single tweet as Tweet
    LinkedList<Tweet> tweetList = new LinkedList<>();

    //List to store the daily sum of tweets, sorted by the day 

    
    
    TreeMap<String, TreeMap<String, Integer>> marketPlayer = new TreeMap<>();    

    String startDate = "";
    String currentDate = "";
    
    
    String [] args = {"--no-save"};
    Rengine rEngine = new Rengine(args, false, new TextConsole());

    public ReadStream() throws IOException {
        //Start reading tweets from file
        System.out.println("Reading Tweets....");
        //readTweets("preprocessedTweets.txt");
        readTweets("preprocessedTweets_june_july_2015_AMZN_cleaned.txt");

        //Test R
        if (!rEngine.waitForR()) {
            System.out.println("Cannot load R");
        }
    }

    public void processTweets() {
        startDate = tweetList.getFirst().getTweetDate().getYear() + "," + tweetList.getFirst().getTweetDate().getDayOfYear();
        currentDate = startDate;
        
        //Da Tweets in unsortierter riehenfolge vorliegen können, sortieren wir diese selber vor der verwarbeitung.
        Collections.sort(tweetList,new Comparator<Tweet>(){
        	@Override
        	public int compare(final Tweet lhs,Tweet rhs) {        		
        		return lhs.getTweetDate().compareTo(rhs.getTweetDate());
        	}
        });
        
    	for (Tweet t : tweetList) {    		
    		execute(t);                  
        }
    	
        detectEventForMarketplayer("AMZN");
        //TODO: seasonal stuff, differencing, etc
    }

	public void detectEventForMarketplayer(String stockSymbol) {
		//Convert dailySum into a vector
        int[] dailySumVector = marketPlayer.get(stockSymbol).values().stream().mapToInt(i -> i).toArray();
        System.out.println(Arrays.toString(dailySumVector));
        int forecastReach = 20;

        rEngine.assign("v", dailySumVector);
        rEngine.eval("startDate <- c(" + startDate + ")");
        //use 365.25 to account for leap years - but points get ugly then...
        //including leap stuff, weekends etc is really complicated
        rEngine.eval("tseries <- ts(v, start=c(startDate),frequency=365)");	//end=c(endDate)
        rEngine.eval("library(forecast)");
        rEngine.eval("autoModel <- auto.arima(tseries)");
        rEngine.eval("autoForecast <- forecast(autoModel,h=" + forecastReach + ")");
        //rEngine.eval("write.table(autoForecast,'output.txt')");
        rEngine.eval("postscript('"+ stockSymbol + ".eps')");
        rEngine.eval("plot(autoForecast)");
        rEngine.eval("dev.off()");

        //need atleast a couple of values for reasonable training
        for (int i = 4; i < dailySumVector.length-1; i++) {
            //with i = 0 you get only the first value, NOT an empty window
            rEngine.eval("subWin <- window(tseries, end=(c(startDate[1],startDate[2]+" + i + ")))");
            rEngine.eval("subForecast <- forecast(auto.arima(subWin),h=1)");
            double[] upperLimit = rEngine.eval("subForecast$upper").asDoubleArray();
            //high95 check
            if(upperLimit[1] < dailySumVector[i+1]) {
                System.out.println("event??");
                System.out.println("forecast: " + rEngine.eval("as.numeric(subForecast$mean)").asDouble());
                System.out.println("upperlimit95: " + upperLimit[1]);
                System.out.println("actual: " + dailySumVector[i+1]);
            }
        }
	}

    public void execute(Tweet t) {
        String tweetDate = t.getTweetDate().getYear() + "," + t.getTweetDate().getDayOfYear();
        
        if (!currentDate.equals(tweetDate)) {
        	for(TreeMap<String, Integer> mPlayer:marketPlayer.values()) {
        		if (mPlayer.get(tweetDate) == null) {
        			mPlayer.put(tweetDate, 0);
        		}   
        	}
        }        
        
        currentDate = tweetDate;
        
        String [] hash = t.getTweetHashtag();
        
        for(String h:hash){
        	if (marketPlayer.get(h) != null) {
        		//TreeMap<String, Integer> ttt = marketPlayer.get(h);
        		
        		
        		if (marketPlayer.get(h).get(currentDate) != null) {
        			marketPlayer.get(h).put(currentDate, marketPlayer.get(h).get(currentDate) + 1);
                } else {
                	marketPlayer.get(h).put(currentDate, 1);
                }
        	} else {
        		marketPlayer.put(h, new TreeMap<String, Integer>());
        		
        		int sYear = Integer.parseInt(startDate.split(",") [0]);
        		int sDay = Integer.parseInt(startDate.split(",") [1]);
        		int cYear = Integer.parseInt(currentDate.split(",") [0]);
        		int cDay = Integer.parseInt(currentDate.split(",") [1]);
        		
        		for(int y = sYear; y<=cYear; y++) {
        			for(int d = sDay; d<cDay; d++) {
        				marketPlayer.get(h).put(y+","+d, 0);
        			}
        		}
        		marketPlayer.get(h).put(currentDate, 1);       
        	}
        }


        
    }

    private void readTweets(String filename) throws FileNotFoundException, IOException {
        //Read the file
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String fileLine;

        while ((fileLine = br.readLine()) != null) {
            //Delete "{", "}", "\n" from the tweet
            fileLine = fileLine.replace("{", "");
            fileLine = fileLine.replace("}", "");

            //Split tweet at ";" and fill tweet List
            String[] tweetData = fileLine.split(";");

            //ID
            double tweetId = Double.parseDouble(tweetData[0]);

            //Date
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss zzz yyyy", Locale.ENGLISH);
            LocalDateTime tweetDate = LocalDateTime.parse(tweetData[1], formatter);

            //Hashtag
            tweetData[2] = tweetData[2].replace("[", "");
            tweetData[2] = tweetData[2].replace("]", "");
            String[] tweetHashtag = tweetData[2].split(",");
            
            for(int i=0; i<tweetHashtag.length; i++) {
            	tweetHashtag[i] = tweetHashtag[i].trim();
            }
           
            //Remove Duplicates TODO: Siehe Mail
            tweetHashtag = new HashSet<String>(Arrays.asList(tweetHashtag)).toArray(new String[0]);
            
            //Full Text
            String tweetText = tweetData[3];

            //Creating new tweet
            Tweet t = new Tweet(tweetId, tweetDate, tweetHashtag, tweetText);

            //Add tweet to tweetList
            tweetList.add(t);
        }
        //Finished reading, closing BufferedReader
        br.close();
    }

    public void exit() {
        rEngine.end();
    }
}
