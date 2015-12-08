package main;

import java.time.LocalDateTime;
import java.util.Arrays;

//Class Tweet
public class Tweet {
	
	private double tweetId;
	private LocalDateTime tweetDate;
	private String[] tweetHashtag;
	private String tweetText;
	
	public Tweet(double tweetId, LocalDateTime tweetDate, String[] tweetHashtag, String tweetText) {
		this.tweetId = tweetId;
		this.tweetDate = tweetDate;
		this.tweetHashtag = tweetHashtag;
		this.tweetText = tweetText;
	}
	
	public double getTweetId() {
		return tweetId;
	}

	public LocalDateTime getTweetDate() {
		return tweetDate;
	}

	public String[] getTweetHashtag() {
		return tweetHashtag;
	}

	public String getTweetText() {
		return tweetText;
	}	
	
	@Override
	public String toString() {
		String ret = "Tweet - tweetId: " + tweetId + " tweetDate: "  + tweetDate + " tweetHashtag: " + Arrays.toString(tweetHashtag) + " tweetText: " + tweetText;
		return ret;
	}
}
