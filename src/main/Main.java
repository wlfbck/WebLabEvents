package main;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        //Creating a new ReadStream object
        ReadStream rs = new ReadStream();

        //Start reading tweets from file
        System.out.println("Reading Tweets...");
        //preprocessedTweets_july_2015_AMZN
        //preprocessedTweets_june_july_2015_AMZN_selection
        //preprocessedTweets_oct2014_until_dec2015_aapl_amzn_fb
        rs.readTweets("preprocessedTweets_june_july_2015_AMZN_cleaned.txt");

        //Start event detection
        System.out.println("Processing Tweets...");
        rs.processTweets();

        //Programm finished
        System.out.println("\nDone, exiting!");
        rs.exit();
    }
}
