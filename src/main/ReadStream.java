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
	
    //List to store every single tweet as Tweet
    LinkedList<Tweet> tweetList = new LinkedList<>();

    //List to store the daily sum of tweets for each hashtag
    TreeMap<String, TreeMap<String, Integer>> marketPlayer = new TreeMap<>();    

    //Start date and current date for counting tweets
    String startDate = "";
    String currentDate = "";
    
    //R engine to buildSum r code within Java. Replacing "null" with "new TextConsole()" will display the R console output 
    String [] rArgs = {"--no-save"};
    Rengine rEngine = new Rengine(rArgs, false, null);

    //ReadStream constructor 
    public ReadStream() throws IOException {
    	//Test if R is running 
        if (!rEngine.waitForR()) {
            System.out.println("Cannot load R -> Exiting");
            System.exit(0);
        }       
    }

    //Reading tweets from file, parsing them "Tweets" and store them in the "tweetList"
    public void readTweets(String filename) throws FileNotFoundException, IOException {
        //Read the given file per line
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String fileLine;

        while ((fileLine = br.readLine()) != null) {
            //Delete "{" and "}" from the tweet
            fileLine = fileLine.replace("{", "");
            fileLine = fileLine.replace("}", "");

            //Split tweet at ";" and fill tweetData
            String[] tweetData = fileLine.split(";");

            //Parse the tweet id
            double tweetId = Double.parseDouble(tweetData[0]);

            //Parse the tweet date
            DateTimeFormatter tFormatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss zzz yyyy", Locale.ENGLISH);
            LocalDateTime tweetDate = LocalDateTime.parse(tweetData[1], tFormatter);

            //Parse the tweet hashtag
            tweetData[2] = tweetData[2].replace("[", "");
            tweetData[2] = tweetData[2].replace("]", "");
            String[] tweetHashtag = tweetData[2].split(",");
            
            //Trim hashtags
            for(int i=0; i<tweetHashtag.length; i++) {
            	tweetHashtag[i] = tweetHashtag[i].trim();
            }
           
            //Remove duplicate hastags
            tweetHashtag = new HashSet<String>(Arrays.asList(tweetHashtag)).toArray(new String[0]);
            
            //Parse the tweet text
            String tweetText = tweetData[3];

            //Just store vaild tweets in the "tweetList"
            if (tweetHashtag.length > 0 && !tweetHashtag[0].equals("")) {
                //Creating a new tweet
                Tweet t = new Tweet(tweetId, tweetDate, tweetHashtag, tweetText);            	
            	//Add tweet to tweetList
                tweetList.add(t);
            } 
           
        }
        //Finished reading, closing BufferedReader
        br.close();
    }
    
    //Trying to find events, counting daily tweet sums for each hashtag and than calling Rs auto arima function
    public void processTweets() {
        //Setting the start date and current date for counting
    	startDate = tweetList.getFirst().getTweetDate().getYear() + "," + tweetList.getFirst().getTweetDate().getDayOfYear();
        currentDate = startDate;
        
        //Time ordering tweets 
        Collections.sort(tweetList,new Comparator<Tweet>(){
        	@Override
        	public int compare(final Tweet lhs,Tweet rhs) {        		
        		return lhs.getTweetDate().compareTo(rhs.getTweetDate());
        	}
        });
        
        //Counting daily tweet sums for each hashtag
    	for (Tweet t : tweetList) {    		
    		buildSum(t);                  
        }
    	
    	//Event detection by calling Rs auto arima function
        detectEventForMarketplayer("AMZN");
        
        //TODO: (??) seasonal stuff, differencing, etc
    }

	//Event detection by calling Rs auto arima function
	public void detectEventForMarketplayer(String stockSymbol) {
		//Convert dailySum into a vector
        int[] dailySumVector = marketPlayer.get(stockSymbol).values().stream().mapToInt(i -> i).toArray();
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

        //need at least a couple of values for reasonable training
        for (int i = 4; i < dailySumVector.length-1; i++) {
            //with i = 0 you get only the first value, NOT an empty window
            rEngine.eval("subWin <- window(tseries, end=(c(startDate[1],startDate[2]+" + i + ")))");
            rEngine.eval("subForecast <- forecast(auto.arima(subWin),h=1)");
            double[] upperLimit = rEngine.eval("subForecast$upper").asDoubleArray();
            //high95 check
            if(upperLimit[1] < dailySumVector[i+1]) {
                System.out.println("\nEvent (?):");
                System.out.println("Forecast: " + rEngine.eval("as.numeric(subForecast$mean)").asDouble());
                System.out.println("UpperLimit95: " + upperLimit[1]);
                System.out.println("Actual: " + dailySumVector[i+1]);
            }
        }
	}

    public void buildSum(Tweet t) {
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

    public void exit() {
        rEngine.end();
    }
}
