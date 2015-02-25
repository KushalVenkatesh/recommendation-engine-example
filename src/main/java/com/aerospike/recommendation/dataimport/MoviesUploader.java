package com.aerospike.recommendation.dataimport;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import org.apache.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.Value;
import com.aerospike.client.large.LargeList;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.recommendation.dataimport.model.Customer;
import com.aerospike.recommendation.dataimport.model.Movie;
import com.aerospike.recommendation.dataimport.model.WatchedRated;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import com.mongodb.WriteConcern;

public class MoviesUploader {
	private static Logger log = Logger.getLogger(MoviesUploader.class);

	private  AerospikeClient aerospikeClient;
	private  String namespace;
	private  String customerSet;
	private  String movieSet;

	private  int counter = 0;
	private  int errors = 0;
	private  boolean aero = false;
	private  boolean mongo = false;

	private  MongoClient mongoClient;
	private  DB mongoDB;
	private  DBCollection movieCollection;
	private  DBCollection customerCollection;
	private  WritePolicy insertPolicy;
	private  WritePolicy updatePolicy;

	


	public void loadData(String host, int port, String namespace, String dbType, File ratingDir, int limit) throws IOException, AerospikeException, ParseException, org.json.simple.parser.ParseException{

		log.debug("Host: " + host);
		log.debug("Port: " + port);
		log.debug("Name space: " + namespace);

		this.namespace = namespace;
		
		
		if (dbType.equalsIgnoreCase("both")){
			aero = true;
			mongo = true;
		} else if (dbType.equalsIgnoreCase("aero")){
			aero = true;
		} else if (dbType.equalsIgnoreCase("mongo")){
			mongo = true;
		}

		log.debug("Limit: " + limit);

		if (aero) {
			aerospikeClient = new AerospikeClient(host, port);
			insertPolicy = new WritePolicy(aerospikeClient.writePolicyDefault);
			insertPolicy.recordExistsAction = RecordExistsAction.CREATE_ONLY;
			updatePolicy = new WritePolicy(aerospikeClient.writePolicyDefault);
			updatePolicy.recordExistsAction = RecordExistsAction.UPDATE_ONLY;
			
			customerSet = Customer.USERS_SET;
			movieSet = Movie.PRODUCT_SET;
		}
		if (mongo){
			mongoClient = new MongoClient(host, port);
			mongoClient.setWriteConcern(WriteConcern.JOURNALED);
			mongoDB = mongoClient.getDB(namespace);
			customerCollection = mongoDB.getCollection(Customer.USERS_SET);
			movieCollection = mongoDB.getCollection(Movie.PRODUCT_SET);
		}
//		File completed = new File(ratingDir.getAbsolutePath() + "/completed");
//		if (!completed.exists()){
//			completed.mkdirs();
//		}
		File[] ratingFiles = ratingDir.listFiles(new FileFilter() {

			@Override
			public boolean accept(File file) {
				return file.getName().startsWith("movie_000") && file.getName().endsWith(".json");
			}
		});
		// process each rating file

		for (File ratingFile : ratingFiles){
			processRatingFile(ratingFile);
			counter++;
//			String moveCmd = "mv " + ratingFile.getAbsolutePath() + " "+completed.getAbsolutePath() + "/" + ratingFile.getName();
//			Runtime.getRuntime().exec(moveCmd);
			if (limit != 0 && counter == limit)
				break;
		}
		log.info("Completed " + counter + " with " + errors + " errors");
	}
	private  void processRatingFile(File file) throws IOException, AerospikeException, ParseException {
		if (!checkFileExists(file)) return;
		log.info("Starting " + file.getName());
		
		JSONParser parser = new JSONParser();

		Object obj = parser.parse(new FileReader(file));
		JSONObject jsonMovie = (JSONObject) obj;

		Movie movie = new Movie(jsonMovie);
		log.info("Processing " + movie.getTitle() + " watched by: " + movie.getWatchedBy().size());
		movie.sortWatched();
		try {
			if (aero)
				saveMovieToAerospike(namespace, movieSet, movie);
			if (mongo)
				saveMovieToMongo(jsonMovie, movie);
			log.info("Saved " + movie.getTitle());
		} catch (AerospikeException e){
			errors++;
			log.error("Aerospike Error", e);
		}
		
		log.info("Processed " + file.getName());

	}

	private  void saveMovieToAerospike(String nameSpace, String set, Movie movie) throws AerospikeException {
		int errors = 0, size = 0;
		Key key =  movie.getKey(nameSpace, set);
		LargeList ratings = aerospikeClient.getLargeList(this.insertPolicy, key, Movie.WATCHED_BY+"List", null);
		List<WatchedRated> ratingList = movie.getWatchedBy();
		size = ratingList.size();
		try {
			try {
				for (WatchedRated wr : ratingList){
					ratings.add(Value.getAsMap(wr));
					addMovieAeroToCustomer(wr);
				}
				aerospikeClient.put(this.updatePolicy, 
						key, 
						movie.asBins());
			} catch (AerospikeException e) {
				if (e.getResultCode() != ResultCode.KEY_EXISTS_ERROR)
					throw e;
			}
		} catch (AerospikeException e) {
			log.error(e.getMessage());
			log.debug(e.getMessage(), e);
			errors++;
		}
		log.debug("Aero Ratings " + size + " saved " + movie.getCountOfRatings() + " with " + errors + " errors");
	}
	private  void saveMovieToMongo(JSONObject jsonMovie, Movie movie)  {
		
		BasicDBObject doc = new BasicDBObject();
		doc.put("database", namespace);
		doc.put("table", movieSet);
		doc.put("movie", jsonMovie);
		movieCollection.insert(doc);

		List<WatchedRated> ratingList = movie.getWatchedBy();
		
		int count = 0, errors = 0;
		for (WatchedRated wr : ratingList){
				addMovieToMongoCustomer(wr);
		}
		log.debug("Mongo Ratings " + ratingList.size() + " saved " + count + " with " + errors + " errors");
	}

	private  void addMovieToMongoCustomer(WatchedRated wr) {
		String customerID = wr.getCustomerID();
		BasicDBObject whereQuery = new BasicDBObject();
		BasicDBList jsonWatched;
		whereQuery.put(Customer.CUSTOMER_ID, customerID);
		BasicDBObject customer = (BasicDBObject) customerCollection.findOne(whereQuery);
		if (customer == null){
			customer = new BasicDBObject();
			customer.append(Customer.CUSTOMER_ID, customerID);
			customer.append(Customer.WATCHED, wr);
			jsonWatched = new BasicDBList();
			jsonWatched.add(wr);
			customer.put(Customer.WATCHED, jsonWatched);
			customerCollection.insert(WriteConcern.SAFE, customer);
		} else {
			jsonWatched = (BasicDBList) customer.get(Customer.WATCHED);
			jsonWatched.add(wr);
			customer.put(Customer.WATCHED, jsonWatched);
			customerCollection.update(customer, jsonWatched);
		}
		
		
		

	}
	private  void addMovieAeroToCustomer(WatchedRated wr) throws AerospikeException{
		Customer customer = null;
		String customerID = null;

		customerID = wr.getCustomerID();

		Record record = aerospikeClient.get(null, new Key(namespace, customerSet, customerID));
		if (record != null)
			customer = new Customer(customerID, record);
		else
			customer = new Customer(customerID);

		// create rated list
		LargeList customerRatingList = aerospikeClient.getLargeList(this.updatePolicy, 
				customer.getKey(namespace, customerSet), 
				Customer.WATCHED, null);
		// Add rated movie to stack
		int count = customer.incrementCount();
		wr.put("key", count);
		customerRatingList.add(Value.getAsMap(wr));
		aerospikeClient.put(this.updatePolicy, 
				customer.getKey(namespace, customerSet), 
				new Bin(Customer.CUSTOMER_ID, Value.get(customer.getCustomerId())),
				new Bin(Customer.RATINGS_COUNT, Value.get(customer.getRatingsCount())));

		log.trace("\tAdded movie " + wr.getMovie() + " to " + customerID);
		customer = null;

	}
	private boolean checkFileExists(File file){
		if (!file.exists()) {
			return false;
		}
		return true;

	}

}
