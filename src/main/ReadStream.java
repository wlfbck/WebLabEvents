package main;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.TreeMap;
import org.rosuda.JRI.Rengine;

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
    TreeMap<String, Integer> dailySum = new TreeMap<>();

    Rengine rEngine = new Rengine(null, false, new TextConsole());

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
        for (Tweet t : tweetList) {
            execute(t);
        }
        //Convert dailySum into a vector
        int[] dailySumVector = dailySum.values().stream().mapToInt(i -> i).toArray();
        int forecastReach = 20;

        rEngine.assign("v", dailySumVector);
        //use 365.25 to account for leap years - but points get ugly then...
        //including leap stuff, weekends etc is really complicated
        rEngine.eval("tseries <- ts(v, start=c(" + dailySum.firstKey() + "),frequency=365)");
        rEngine.eval("library(forecast)");
        rEngine.eval("autoModel <- auto.arima(tseries)");
        rEngine.eval("autoForecast <- forecast(autoModel,h=" + forecastReach + ")");
        rEngine.eval("write.table(autoForecast,'output.txt')");
        rEngine.eval("jpeg('yolo.jpeg')");
        rEngine.eval("plot(autoForecast)");
        rEngine.eval("dev.off()");
        
        //TODO: get subwindows
        //R command: window(tseries, end=c(end(tseries)[1],end(tseries)[2]-3))
        //end(tseries)[1] is the end-year = (2015 for current data)
        //end(tseries)[2]-3 is the end-day minus 3 = (209 for current data)
        
        //TODO: compare forecast for subwindows and see how they match up with the real data.
        //4 latter columns in output specify likelihood thresholds. maybe use those?
        
        //TODO: seasonal stuff, differencing, etc
    }

    public void execute(Tweet t) {
        String str = t.getTweetDate().getYear() + "," + t.getTweetDate().getDayOfYear();
        if (dailySum.get(str) != null) {
            dailySum.put(str, dailySum.get(str) + 1);
        } else {
            dailySum.put(str, 1);
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
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss zzz yyyy");
            LocalDateTime tweetDate = LocalDateTime.parse(tweetData[1], formatter);

            //Hashtag
            tweetData[2] = tweetData[2].replace("[", "");
            tweetData[2] = tweetData[2].replace("]", "");
            String[] tweetHashtag = tweetData[2].split(",");

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
