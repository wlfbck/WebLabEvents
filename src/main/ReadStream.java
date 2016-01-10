package main;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TreeMap;
import org.rosuda.JRI.REXP;
import org.rosuda.JRI.Rengine;

/*
POSSIBLE THINGS TO DO:
ignore bad arima models and just continue (faster, but prolly errorneous)

increase datawindow to prevent bad arima models (slower because windowsize, but faster cause no double calls)

dont check for every tweet but for every i-th (increasingly faster)

port arima and automodelling to java (HUGE time investment but probably huge speedup and a lot of things get easier)

TODO:
implement eventlist or other output and skip all regular output and image generation

integrate into apache storm pipeline

 */
public class ReadStream {

    //List to store every single tweet as Tweet
    LinkedList<Tweet> tweetList = new LinkedList<>();

    //List to store the sum of tweets for each hashtag
    //each unit in here has the length of tweetSumWindow
    TreeMap<String, TreeMap<Long, Integer>> marketPlayer = new TreeMap<>();

    //Start and current "date" for counting tweets in tweetSumWindow
    long startDate;
    long currentDate;

    //R engine to buildSum r code within Java. Replacing "null" with "new TextConsole()" will display the R console output 
    String[] rArgs = {"--no-save"};
    Rengine rEngine = new Rengine(rArgs, false, null);

    //time window in minutes in which tweets get aggregated
    int tweetSumWindow;
    //units of time to aggregate for coarsening (at evaluation time)
    int numberOfSumOfSums;
    //units of time to look back on - in evalWindow units
    int dataWindow;

    //Marketplayers to look for
    String[] stockSymbols;

    //ReadStream constructor 
    public ReadStream() throws IOException {
        //Test if R is running 
        if (!rEngine.waitForR()) {
            System.out.println("Cannot load R -> Exiting");
            System.exit(0);
        }
        rEngine.eval("library(forecast)");
        rEngine.eval("capture = function(data) {message=capture.output(auto.arima(data),type=\"message\")}");

        this.tweetSumWindow = 60;
        this.dataWindow = 5;
        this.numberOfSumOfSums = 24;

        stockSymbols = new String[]{"AMZN"};
    }

    //Trying to find events, counting daily tweet sums for each hashtag and than calling Rs auto arima function
    public void processTweets() {
        LocalDateTime tweetD = tweetList.getFirst().getTweetDate();

        //Setting the start date and current date for counting
        startDate = tweetD.toEpochSecond(ZoneOffset.UTC) / (60 * tweetSumWindow);
        currentDate = startDate;

        //Counting daily tweet sums for each hashtag
        for (Tweet t : tweetList) {
            boolean dateHasChanged = addToTweetSums(t);
            for (String symbol : stockSymbols) {
                if (dateHasChanged) {
                    calculateCurrentModelForMarketplayer(symbol);
                }
                detectEventForTweet(symbol);
            }
        }
        //detectEventsForMarketplayer("AMZN");
    }

    //Detection on a per day basis.
    //Plots whole timeline with 20day forecast.
    public void detectEventsForMarketplayer(String stockSymbol) {
        //Convert dailySum into a vector
        int[] sumVector = marketPlayer.get(stockSymbol).values().stream().mapToInt(i -> i).toArray();
        int shrinkageFactor = 24 * 60 / tweetSumWindow;
        int[] dailySumVector = new int[sumVector.length / shrinkageFactor + 1];
        int retIndex = 0;
        for (int i = 0; i < sumVector.length - 1; i++) {
            retIndex = i / shrinkageFactor;
            dailySumVector[retIndex] += sumVector[i];
        }

        int forecastReach = 20;

        LocalDateTime startPoint = LocalDateTime.ofEpochSecond(startDate * 60 * tweetSumWindow, 0, ZoneOffset.UTC);

        rEngine.assign("v", dailySumVector);
        rEngine.eval("startDate <- c(" + startPoint.getYear() + "," + startPoint.getDayOfYear() + ")");
        //use 365.25 to account for leap years - but points get ugly then...
        //including leap stuff, weekends etc is really complicated
        rEngine.eval("tseries <- ts(v, start=c(startDate),frequency=365)");	//end=c(endDate)
        rEngine.eval("autoModel <- auto.arima(tseries)");
        rEngine.eval("autoForecast <- forecast(autoModel,h=" + forecastReach + ")");
        //rEngine.eval("write.table(autoForecast,'output.txt')");
        rEngine.eval("jpeg('" + stockSymbol + ".jpeg',width=1440,height=900)");
        rEngine.eval("plot(autoForecast)");
        rEngine.eval("dev.off()");

        //need at least a couple of values for reasonable training
        for (int i = 4; i < dailySumVector.length - 1; i++) {
            //with i = 0 you get only the first value, NOT an empty window
            rEngine.eval("subWin <- window(tseries, end=(c(startDate[1],startDate[2]+" + i + ")))");
            rEngine.eval("subForecast <- forecast(auto.arima(subWin),h=1)");
            double[] upperLimit = rEngine.eval("subForecast$upper").asDoubleArray();
            //high95 check
            if (upperLimit[1] < dailySumVector[i + 1]) {
                //i+1 for the date since i=0 is the first day
                LocalDateTime now = LocalDateTime.ofEpochSecond((startDate) * 60 * tweetSumWindow + (i + 1) * 24 * 60 * 60, 0, ZoneOffset.UTC);
                System.out.println("\nEvent (?) at " + now.getYear() + "," + now.getDayOfYear() + ":");
                System.out.println("Forecast: " + rEngine.eval("as.numeric(subForecast$mean)").asDouble());
                System.out.println("UpperLimit95: " + upperLimit[1]);
                System.out.println("Actual: " + dailySumVector[i + 1]);

                rEngine.eval("jpeg('" + stockSymbol + i + ".jpeg',width=1440,height=900)");
                rEngine.eval("plot(subForecast,ylim=c(min(min(subForecast$lower),min(subForecast$x)),max(tseries[" + (i + 2) + "],max(subForecast$x))))");
                //the indexing in R is weird, so we need +2 there...
                rEngine.eval("points(startDate[1]+(startDate[2]+" + i + ")/365,tseries[" + (i + 2) + "],pch=19,col='red')");
                rEngine.eval("dev.off()");
            }
        }
    }

    public void detectEventForTweet(String stockSymbol) {
        REXP r = rEngine.eval("subForecast" + stockSymbol + "$upper");
        if(r==null) {
            return;
        }
        double[] upperLimit = r.asDoubleArray();
        double actualValue = getLastDataValue(stockSymbol);

        //high95 check
        if (upperLimit[1] < actualValue) {
            int timeFactor = (24 * 60) / (tweetSumWindow * numberOfSumOfSums);
            int f = 365 * timeFactor;
            LocalDateTime ldt = LocalDateTime.ofEpochSecond(currentDate * 60 * tweetSumWindow, 0, ZoneOffset.UTC);
            int currentWindow = (ldt.getDayOfYear() * 24 * 60 + ldt.getHour() + ldt.getMinute() - tweetSumWindow) / (tweetSumWindow * numberOfSumOfSums);
            
            System.out.println("\nEvent (?)at " + ldt + ":");
            System.out.println("Forecast: " + rEngine.eval("as.numeric(subForecast" + stockSymbol + "$mean)").asDouble());
            System.out.println("UpperLimit95: " + upperLimit[1]);
            System.out.println("Actual: " + actualValue);

            rEngine.eval("jpeg('" + stockSymbol + currentWindow + ".jpeg',width=1440,height=900)");
            rEngine.eval("plot(subForecast" + stockSymbol + ",ylim=c(min(min(subForecast" + stockSymbol + "$lower),min(subForecast" + stockSymbol + "$x)),max(" + actualValue + ",max(subForecast" + stockSymbol + "$x))))");
            rEngine.eval("points(endDate[1]+endDate[2]/" + f + "," + actualValue + ",pch=19,col='red')");
            rEngine.eval("dev.off()");
        }
    }

    //calculates model for given stocksymbol and saves the forecast in R under 'subForecast'+stockSymbol
    public void calculateCurrentModelForMarketplayer(String stockSymbol) {
        int[] dataWindowValues = getDataset(stockSymbol);
        if (dataWindowValues == null) {
            return;//not enough values yet
        }

        int timeFactor = (24 * 60) / (tweetSumWindow * numberOfSumOfSums);
        int f = 365 * timeFactor;
        LocalDateTime ldt = LocalDateTime.ofEpochSecond(currentDate * 60 * tweetSumWindow, 0, ZoneOffset.UTC);

        int[] dataForR = Arrays.copyOf(dataWindowValues, dataWindowValues.length - 1);
        rEngine.assign("v", dataForR);
        int currentWindow = (ldt.getDayOfYear() * 24 * 60 + ldt.getHour() + ldt.getMinute() - tweetSumWindow) / (tweetSumWindow * numberOfSumOfSums);
        rEngine.eval("endDate <- c(" + ldt.getYear() + "," + currentWindow + ")");
        rEngine.eval("tseries <- ts(v, end=endDate,frequency=" + f + ")");

        REXP x = rEngine.eval("capture(tseries)");
        if (x.asStringArray().length > 0) {
            System.out.println("data is non-stationary, cannot apply ARIMA, skipping dataset");
            return;
        }

        rEngine.eval("subForecast" + stockSymbol + " <- forecast(auto.arima(tseries),h=1)");
    }

    //building more coarse dataset
    public int[] getDataset(String stockSymbol) {
        int[] dataset = marketPlayer.get(stockSymbol).values().stream().mapToInt(i -> i).toArray();

        //not enough data yet
        //+1 because we want to forcast for the current window, therefore we ignore the current. 
        if (dataset.length - ((dataWindow + 1) * numberOfSumOfSums) < 0) {
            return null;
        }

        int[] retSet = new int[dataWindow + 1];
        int retIndex;
        int startVal = dataset.length - (dataWindow + 1) * numberOfSumOfSums;
        for (int i = startVal; i < dataset.length; i++) {
            retIndex = (i - startVal) / numberOfSumOfSums;
            retSet[retIndex] += dataset[i];
        }

        return retSet;
    }

    public int getLastDataValue(String stockSymbol) {
        int[] dataset = marketPlayer.get(stockSymbol).values().stream().mapToInt(i -> i).toArray();

        //not enough data yet
        //+1 because we want to forcast for the current window, therefore we ignore the current. 
        if ((dataset.length - numberOfSumOfSums) < 0) {
            return -1;
        }

        int retValue = 0;
        int startVal = dataset.length - numberOfSumOfSums;
        for (int i = startVal; i < dataset.length; i++) {
            retValue += dataset[i];
        }

        return retValue;
    }

    //Calculating daily sums of tweets for each hashtag
    //returns whether the currentDate changed or not
    public boolean addToTweetSums(Tweet t) {
        boolean dateHasChanged = false;
        //Building a string of the tweetDate
        long tweetDateL = t.getTweetDate().toEpochSecond(ZoneOffset.UTC) / (60 * tweetSumWindow);

        //each new timeunit, we have to insert empty values for all marketplayer
        if (currentDate != tweetDateL) {
            System.out.println(LocalDateTime.ofEpochSecond(currentDate * 60 * tweetSumWindow, 0, ZoneOffset.UTC));
            for (TreeMap<Long, Integer> mPlayer : marketPlayer.values()) {
                if (mPlayer.get(tweetDateL) == null) {
                    mPlayer.put(tweetDateL, 0);
                }
            }
            currentDate = tweetDateL;
            dateHasChanged = true;
        }

        String[] hash = t.getTweetHashtag();
        for (String h : hash) {
            if (marketPlayer.get(h) != null) {
                //because of putting 0 for all marketplayers on each new timeunit, we don't need to check if the value is already there
                marketPlayer.get(h).put(currentDate, marketPlayer.get(h).get(currentDate) + 1);
            } else {
                marketPlayer.put(h, new TreeMap<>());
                for (long min = startDate; min <= currentDate; min++) {
                    marketPlayer.get(h).put(min, 0);
                }
                marketPlayer.get(h).put(currentDate, 1);
            }
        }

        return dateHasChanged;
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
            for (int i = 0; i < tweetHashtag.length; i++) {
                tweetHashtag[i] = tweetHashtag[i].trim();
            }

            //Remove duplicate hastags
            tweetHashtag = new HashSet<>(Arrays.asList(tweetHashtag)).toArray(new String[0]);

            //Parse the tweet text
            //String tweetText = tweetData[3];
            //Just store vaild tweets in the "tweetList"
            if (tweetHashtag.length > 0 && !tweetHashtag[0].equals("")) {
                //Creating a new tweet
                Tweet t = new Tweet(tweetId, tweetDate, tweetHashtag, null);
                //Add tweet to tweetList
                tweetList.add(t);
            }

        }
        //Finished reading, closing BufferedReader
        br.close();

        //Time ordering tweets 
        Collections.sort(tweetList, (final Tweet lhs, Tweet rhs) -> lhs.getTweetDate().compareTo(rhs.getTweetDate()));
    }

    //Closing R
    public void exit() {
        rEngine.end();
    }
}
