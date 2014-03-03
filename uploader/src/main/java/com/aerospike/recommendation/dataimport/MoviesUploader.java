package com.aerospike.recommendation.dataimport;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.Assert;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Value;
import com.aerospike.client.large.LargeStack;
import com.aerospike.client.policy.Policy;
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

	public static final String MOVIE_DIR = "resources/movies";
	private static AerospikeClient aerospikeClient;
	private static WritePolicy writePolicy;
	private static String namespace;
	private static String customerSet;
	private static String movieSet;

	private static int counter = 0;
	private static int errors = 0;
	private static boolean aero = false;
	private static boolean mongo = false;

	private static MongoClient mongoClient;
	private static DB mongoDB;
	private static DBCollection movieCollection;
	private static DBCollection customerCollection;
	


	public static void main(String[] args) throws Exception{
		Options options = new Options();
		options.addOption("h", "host", true, "Server hostname (default: localhost)");
		options.addOption("p", "port", true, "Server port (default: 3000)");
		options.addOption("n", "namespace", true, "Namespace (default: test)");
		options.addOption("db", "database", true, "Database: aero, mongo, both");
		options.addOption("l", "limit", true, "Limit the number of movies uploaded");
		options.addOption("u", "usage", false, "Print usage.");

		CommandLineParser parser = new PosixParser();
		CommandLine cl = parser.parse(options, args, false);

		if (args.length == 0 || cl.hasOption("u")) {
			logUsage(options);
			return;
		}
		int limit = 0;
		String host = cl.getOptionValue("h", "127.0.0.1");
		String portString = cl.getOptionValue("p", "3000");
		int port = Integer.parseInt(portString);
		namespace = cl.getOptionValue("n","test");

		log.debug("Host: " + host);
		log.debug("Port: " + port);
		log.debug("Name space: " + namespace);

		
		String dbType = cl.getOptionValue("db","both");
		if (dbType.equalsIgnoreCase("both")){
			aero = true;
			mongo = true;
		} else if (dbType.equalsIgnoreCase("aero")){
			aero = true;
		} else if (dbType.equalsIgnoreCase("mongo")){
			mongo = true;
		}

		if (cl.hasOption("l")){
			limit = Integer.parseInt(cl.getOptionValue("l", "0"));
		}
		log.debug("Limit: " + limit);

		if (aero) {
			aerospikeClient = new AerospikeClient(host, port);
			writePolicy = new WritePolicy();
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
		File ratingDir = new File(MOVIE_DIR);
		File completed = new File(ratingDir.getAbsolutePath() + "/completed");
		if (!completed.exists()){
			completed.mkdirs();
		}
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
			String moveCmd = "mv " + ratingFile.getAbsolutePath() + " "+completed.getAbsolutePath() + "/" + ratingFile.getName();
			Runtime.getRuntime().exec(moveCmd);
			if (limit != 0 && counter == limit)
				break;
		}
		log.info("Completed " + counter + " with " + errors + " errors");
	}
	@SuppressWarnings("unchecked")
	private static void processRatingFile(File file) throws IOException, AerospikeException, ParseException {
		if (!checkFileExists(file)) return;
		Policy policy = new Policy();
		policy.timeout = 0;
		JSONParser parser = new JSONParser();

		Object obj = parser.parse(new FileReader(file));
		JSONObject jsonMovie = (JSONObject) obj;

		Movie movie = new Movie(jsonMovie);
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
		
		log.info("Successfully processed " + file.getName());

	}

	private static void saveMovieToAerospike(String nameSpace, String set, Movie movie) throws AerospikeException {
		aerospikeClient.put(writePolicy, 
				movie.getKey(nameSpace, set), 
				movie.asBins());
		LargeStack ratings = aerospikeClient.getLargeStack(writePolicy, movie.getKey(nameSpace, set), Movie.WATCHED_BY+"List", null);
		List<WatchedRated> ratingList = movie.getWatchedBy();
		int count = 0, errors = 0;
		for (WatchedRated wr : ratingList){
			try {
				addMovieAeroToCustomer(wr);
				ratings.push(Value.getAsMap(wr));
				count++;
			} catch (AerospikeException e) {
				errors++;
			}
		}
		log.debug("Aero Ratings " + ratingList.size() + " saved " + count + " with " + errors + " errors");
	}
	private static void saveMovieToMongo(JSONObject jsonMovie, Movie movie)  {
		
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

	private static void addMovieToMongoCustomer(WatchedRated wr) {
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
	private static void addMovieAeroToCustomer(WatchedRated wr) throws AerospikeException{
		Customer customer = null;
		String customerID = null;

		customerID = wr.getCustomerID();


		customer = new Customer(customerID);
		if (!aerospikeClient.exists(writePolicy, 
				customer.getKey(namespace, Customer.USERS_SET))){
			aerospikeClient.put(writePolicy, 
					customer.getKey(namespace, Customer.USERS_SET), 
					new Bin(Customer.CUSTOMER_ID, Value.get(customerID)));
			log.trace("New customer id: " + customerID);
		}

		// create rated stack
		LargeStack customerRatingStack = aerospikeClient.getLargeStack(writePolicy, 
				customer.getKey(namespace, Customer.USERS_SET), 
				Customer.WATCHED, null);
		// Add rated movie to stack
		customerRatingStack.push(Value.getAsMap(wr));
		log.trace("Added movie " + wr.getMovie() + " to " + customerID);
		customer = null;

	}
	private static boolean checkFileExists(File file){
		if (!file.exists()) {
			Assert.fail("File " + file.getName() + " does not extst");
			return false;
		}
		return true;

	}
	private static void logUsage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		String syntax = MoviesUploader.class.getName() + " [<options>]";
		formatter.printHelp(pw, 100, syntax, "options:", options, 0, 2, null);
		log.info(sw.toString());
	}

}
