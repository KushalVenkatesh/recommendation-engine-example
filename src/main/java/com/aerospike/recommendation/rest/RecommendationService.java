package com.aerospike.recommendation.rest;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.util.Properties;

import javax.servlet.MultipartConfigElement;

import com.aerospike.recommendation.dataimport.MoviesUploader;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;

@Configuration
@EnableAutoConfiguration
@ComponentScan
public class RecommendationService {
	private static Logger log = Logger.getLogger(RecommendationService.class);
	
	@Bean
	public AerospikeClient asClient() throws AerospikeException {
		Properties as = System.getProperties();
		AerospikeClient aerospikeClient = new AerospikeClient(as.getProperty("seedHost"), Integer.parseInt(as.getProperty("port")));
		return aerospikeClient;
	}
	@Bean
	public MongoClient mongoClient() throws UnknownHostException {
		Properties as = System.getProperties();
		MongoClient mongoClient = new MongoClient(as.getProperty("seedHost"), Integer.parseInt(as.getProperty("port")));
		return mongoClient;
	}

	@Bean
	public DB mongoDB() throws UnknownHostException{
		Properties as = System.getProperties();
		DB db = mongoClient().getDB(as.getProperty("namespace"));
		return db;		
	}
	
	@Bean
	public DBCollection movieCollection() throws UnknownHostException{
		DBCollection movieCollection = mongoDB().getCollection(RESTController.PRODUCT_SET);
		return movieCollection;
	}
	
	@Bean
	public DBCollection customerCollection() throws UnknownHostException{
		DBCollection customerCollection = mongoDB().getCollection(RESTController.USERS_SET);
		return customerCollection;
	}
	
	@Bean
	public MultipartConfigElement multipartConfigElement() {
		return new MultipartConfigElement("");
	}
	
	public static void main(String[] args) throws ParseException, IOException, AerospikeException, org.json.simple.parser.ParseException {

		Options options = new Options();
		options.addOption("h", "host", true, "Server hostname (default: localhost)");
		options.addOption("p", "port", true, "Server port (default: 3000)");
		options.addOption("n", "namespace", true, "Aerospike namespace (default: test)");
		options.addOption("db", "database", true, "Database: aero, mongo, both");
		options.addOption("l", "limit", true, "Limit the number of movies uploaded");
		options.addOption("m", "movies", true, "Movie file directory");
		options.addOption("u", "usage", false, "Print usage.");
		
		// parse the command line args
		CommandLineParser parser = new PosixParser();
		CommandLine cl = parser.parse(options, args, false);

		if (args.length == 0 || cl.hasOption("u")) {
			logUsage(options);
			return;
		}
		String host = cl.getOptionValue("h", "127.0.0.1");
		String portString = cl.getOptionValue("p", "3000");
		int port = Integer.parseInt(portString);
		String namespace = cl.getOptionValue("n","test");
		String dbType = cl.getOptionValue("db","both");
		log.info("Host: " + host);
		log.info("Port: " + portString);	
		log.info("Namespace: " + namespace);
		log.info("Database: " + dbType);	
		
		if (cl.hasOption("m")){
			// run as the data loader
			int limit = 0;
			if (cl.hasOption("l")){
				limit = Integer.parseInt(cl.getOptionValue("l", "0"));
			}
			log.info("Limit: " + limit);
			File ratingDir = new File(cl.getOptionValue("m","movies"));
			log.info("Data directory: " + ratingDir);

			MoviesUploader ml = new MoviesUploader();
			ml.loadData(host, port, namespace, dbType, ratingDir, limit);

		} else {
			// run as a RESTful service
			// set properties
			Properties as = System.getProperties();
			as.put("seedHost", host);
			
			as.put("port", portString);
			
			as.put("namespace", namespace);
			
			as.put("dataBase", dbType);

			// start app
			SpringApplication.run(RecommendationService.class, args);
		}
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
