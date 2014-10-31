package com.aerospike.recommendation.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.large.LargeStack;
import com.aerospike.client.policy.Policy;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.MongoClient;

@Controller
public class RESTController {
	private static final int MOVIE_REVIEW_LIMIT = 20;
	public static final String NAME_SPACE = "test";
	public static final String PRODUCT_SET = "MOVIE_TITLES";
	public static final String USERS_SET = "MOVIE_CUSTOMERS";

	public static final String DATE = "date";
	public static final String RATING = "rating";
	public static final String CUSTOMER_ID = "customer-id";
	public static final String MOVIE_ID = "movie-id";
	public static final String WATCHED_BY = "watchedBy";
	public static final String TITLE = "title";
	public static final String YEAR_OF_RELEASE = "yearOfRelease";
	public static final String CUSTOMER_WATCHED = "watched";
	private static Logger log = Logger.getLogger(RESTController.class); 
	@Autowired
	AerospikeClient aerospikeClient;

	@Autowired
	MongoClient mongoClient;
	@Autowired
	private DB mongoDB;
	@Autowired
	private DBCollection movieCollection;
	@Autowired
	private DBCollection customerCollection;

	static final String nameSpace;
	static {
		Properties as = System.getProperties();
		nameSpace = (String) as.get("namespace");
	}
	/**
	 * get a recommendation for a specific customer from Aerospike
	 * @param user a unique ID for a customer
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	@RequestMapping(value="/aerospike/recommendation/{customer}", method=RequestMethod.GET)
	public @ResponseBody JSONArray getAerospikeRecommendationFor(@PathVariable("customer") String customerID) throws Exception {
		log.debug("Finding recomendations for " + customerID);
		Policy policy = new Policy();

		/*
		 * get the latest movies watched and rated by the customer
		 */
		LargeStack customerWatched = aerospikeClient.getLargeStack(new Policy(), 
				new Key(NAME_SPACE, USERS_SET, customerID), 
				CUSTOMER_WATCHED, null);
		if (customerWatched == null || customerWatched.size()==0){
			// customer Hasen't Watched anything
			log.debug("No movies found for customer: " + customerID );
			throw new NoMoviesFound(customerID);
		}

		List<Map<String, Object>> customerWatchedList = (List<Map<String, Object>>) customerWatched.peek(MOVIE_REVIEW_LIMIT);
		/*
		 * build a vector list of movies watched
		 */
		List<Long> thisCustomerMovieVector = makeVector(customerWatchedList);


		Record bestMatchedCustomer = null;
		List<Map<String, Object>> bestMatchedList = null;
		double bestScore = 0;
		/*
		 * for each movie this customer watched, iterate
		 * through the other customers that also watched
		 * the movie 
		 */
		for (Map<String, Object> wr : customerWatchedList){
			Key movieKey = new Key(NAME_SPACE, PRODUCT_SET, (String) wr.get(MOVIE_ID) );
			LargeStack whoWatched = aerospikeClient.getLargeStack(new Policy(), 
					movieKey, 
					WATCHED_BY+"List", null);
			/* 
			 * Some movies are watched by >100k customers, only look at the last n movies, or the 
			 * number of customers, whichever is smaller
			 */
			
			List<Map<String, Object>> whoWatchedList = (List<Map<String, Object>>)whoWatched.peek(Math.min(MOVIE_REVIEW_LIMIT, whoWatched.size()));

			if (!(whoWatchedList == null)){
				for (Map<String, Object> watchedBy : whoWatchedList){
					String similarCustomerId = (String) watchedBy.get(CUSTOMER_ID);
					if (!similarCustomerId.equals(customerID)) {
						// find user with the highest similarity

						Record similarCustomer = aerospikeClient.get(policy, new Key(NAME_SPACE, USERS_SET, similarCustomerId));
						LargeStack similarCustomerWatched = aerospikeClient.getLargeStack(new Policy(), 
								new Key(NAME_SPACE, USERS_SET, similarCustomerId), 
								CUSTOMER_WATCHED, null);

						List<Map<String, Object>> similarCustomerWatchedList = (List<Map<String, Object>>) similarCustomerWatched.peek(MOVIE_REVIEW_LIMIT);
						
						double score = easySimilarity(thisCustomerMovieVector, similarCustomerWatchedList);
						if (score > bestScore){
							bestScore = score;
							bestMatchedCustomer = similarCustomer;
							bestMatchedList = similarCustomerWatchedList;
						}
					}
				}
			}
			whoWatched = null;
		}
		log.debug("Best customer: " + bestMatchedCustomer);
		log.debug("Best score: " + bestScore);
		// return the best matched user's purchases as the recommendation
		List<Integer> bestMatchedPurchases = new ArrayList<Integer>();
		for (Map<String, Object> watched : bestMatchedList){
			Integer movieID = Integer.parseInt((String) watched.get(MOVIE_ID));
			if ((!thisCustomerMovieVector.contains(movieID))&&(movieID != null)){
				bestMatchedPurchases.add(movieID);
			}
		}

		// get the movies
		Key[] recomendedMovieKeys = new Key[bestMatchedPurchases.size()];
		int index = 0;
		for (int recomendedMovieID : bestMatchedPurchases){
			recomendedMovieKeys[index] = new Key(NAME_SPACE, PRODUCT_SET, String.valueOf(recomendedMovieID));
			log.debug("Added Movie key: " + recomendedMovieKeys[index]);
			index++;
		}
		Record[] recommendedMovies = aerospikeClient.get(null, recomendedMovieKeys, TITLE, YEAR_OF_RELEASE);

		// This is a diagnostic step
		if (log.isDebugEnabled()){
			log.debug("Recomended Movies:");
			for (Record rec : recommendedMovies){
				log.debug(rec);
			}
		}

		// Turn the Aerospike records into a JSONArray
		JSONArray recommendations = new JSONArray();
		for (Record rec: recommendedMovies){
			if (rec != null)
				recommendations.add(new JSONRecord(rec));
		}
		log.debug("Found these recomendations: " + recommendations);
		return recommendations;
	}
	
	
	/**
	 * get a recommendation for a specific customer from MongoDB
	 * @param user a unique ID for a customer
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	@RequestMapping(value="/mongo/recommendation/{customer}", method=RequestMethod.GET)
	public @ResponseBody BasicDBList getMongoRecommendationFor(@PathVariable("customer") String customerID) throws Exception {
		log.debug("Finding recomendations for " + customerID);

		/* 
		 * Get the customer's purchase history as a list of ratings
		 */
		BasicDBObject thisUser = null;
		BasicDBObject whereQuery = new BasicDBObject();
		whereQuery.put(CUSTOMER_ID, customerID);
		thisUser = (BasicDBObject) customerCollection.findOne(whereQuery);
		if (thisUser == null){
			log.debug("Could not find user: " + customerID );
			throw new CustomerNotFound(customerID);
		}

		/*
		 * get the movies watched and rated
		 */
		List<Map<String, Object>> customerWatched = (List<Map<String, Object>>) thisUser.get(CUSTOMER_WATCHED);
		if (customerWatched == null || customerWatched.size()==0){
			// customer Hasen't Watched anything
			log.debug("No movies found for customer: " + customerID );
			throw new NoMoviesFound(customerID);
		}

		/*
		 * build a vector list of movies watched
		 */
		List<Long> thisCustomerMovieVector = makeVector(customerWatched);


		BasicDBObject bestMatchedCustomer = null;
		double bestScore = 0;
		/*
		 * for each movie this customer watched, iterate
		 * through the other customers that also watched
		 * the movie 
		 */
		BasicDBObject movieRecord;
		BasicDBObject movieQuery = new BasicDBObject();
		BasicDBList jsonWatched;
		
		for (Map<String, Object> wr : customerWatched) {
			movieQuery.put(MOVIE_ID, wr.get(MOVIE_ID));
			movieRecord = (BasicDBObject) movieCollection.findOne(movieQuery);
			

			List<Map<String, Object>> whoWatched = (List<Map<String, Object>>) movieRecord.get(WATCHED_BY);

			if (!(whoWatched == null)){
				int end = Math.min(MOVIE_REVIEW_LIMIT, whoWatched.size()); 
				/* 
				 * Some movies are watched by >100k customers, only look at the last n movies, or the 
				 * number of customers, whichever is smaller
				 */
				for (int index = 0; index < end; index++){
					Map<String, Object> watchedBy = whoWatched.get(index);
					String similarCustomerId = (String) watchedBy.get(CUSTOMER_ID);
					if (!similarCustomerId.equals(customerID)) {
						// find user with the highest similarity
						BasicDBObject similarCustomerQuery = new BasicDBObject();
						whereQuery.put(CUSTOMER_ID, similarCustomerId);
						BasicDBObject similarCustomer = (BasicDBObject) customerCollection.findOne(similarCustomerQuery);

						List<Map<String, Object>> similarCustomerWatched = (List<Map<String, Object>>) similarCustomer.get(CUSTOMER_WATCHED);
						double score = easySimilarity(thisCustomerMovieVector, similarCustomerWatched);
						if (score > bestScore){
							bestScore = score;
							bestMatchedCustomer = similarCustomer;
						}
					}
				}
			}
		}
		log.debug("Best customer: " + bestMatchedCustomer);
		log.debug("Best score: " + bestScore);
		// return the best matched user's purchases as the recommendation
		List<Integer> bestMatchedPurchases = new ArrayList<Integer>();
		for (Map<String, Object> watched : (List<Map<String, Object>>)bestMatchedCustomer.get(CUSTOMER_WATCHED)){
			Integer movieID = Integer.parseInt((String) watched.get(MOVIE_ID));
			if ((!thisCustomerMovieVector.contains(movieID))&&(movieID != null)){
				bestMatchedPurchases.add(movieID);
			}
		}

		// get the movies
		BasicDBList recommendedMovies = new BasicDBList();
		BasicDBObject inQuery = new BasicDBObject();
		inQuery.put(MOVIE_ID, new BasicDBObject("$in", bestMatchedPurchases));
		DBCursor cursor = movieCollection.find(inQuery);
		while(cursor.hasNext()) {
			recommendedMovies.add(cursor.next());
		}

		// This is a diagnostic step
		if (log.isDebugEnabled()){
			log.debug("Recomended Movies:");
			for (Object rec : recommendedMovies){
				log.debug(rec);
			}
		}

		return recommendedMovies;
	}
	/**
	 * Produces a Integer vector from the movie IDs
	 * @param ratingList
	 * @return
	 */
	private List<Long> makeVector(List<Map<String, Object>> ratingList){
		List<Long> movieVector = new ArrayList<Long>();
		for (Map<String, Object> one : ratingList){
			String movieString = (String)one.get(MOVIE_ID);
			if (movieString == null)
				movieVector.add(0L);
			else {
				// Ad the movie ID and rating to the vector
				movieVector.add(Long.parseLong(movieString)); // Movie ID
				movieVector.add(Long.parseLong((String)one.get(RATING))); // Customer Rating
			}
		}
		return movieVector;
	}
	/**
	 * This is a very rudimentary algorithm using Cosine similarity
	 * @param customerWatched
	 * @param similarCustomerWatched
	 * @return
	 */
	public double easySimilarity(List<Long> thisCustomerVector, List<Map<String, Object>> similarCustomerWatched){
		double incommon = 0;
		/*
		 * this is the place where you can create clever
		 * similarity score.
		 * 
		 * This algorithm simple returns how many movies these customers have in common.
		 * 
		 * You could use any similarity algorithm you wish
		 */
		List<Long> similarCustomerVector = makeVector(similarCustomerWatched);

		return CosineSimilarity.cosineSimilarity(thisCustomerVector, similarCustomerVector);
	}

}
