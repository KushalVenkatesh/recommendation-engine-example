package com.aerospike.recommendation.rest;

import java.net.UnknownHostException;
import java.util.Properties;

import javax.servlet.MultipartConfigElement;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
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
	
	public static void main(String[] args) throws ParseException {

		Options options = new Options();
		options.addOption("h", "host", true, "Server hostname (default: localhost)");
		options.addOption("p", "port", true, "Server port (default: 3000)");
		options.addOption("n", "namespace", true, "Aerospike namespace (default: test)");
		options.addOption("db", "database", true, "Database: aero, mongo, both");
		
		// parse the command line args
		CommandLineParser parser = new PosixParser();
		CommandLine cl = parser.parse(options, args, false);

		// set properties
		Properties as = System.getProperties();
		String host = cl.getOptionValue("h", "localhost");
		as.put("seedHost", host);
		String portString = cl.getOptionValue("p", "3000");
		as.put("port", portString);
		String nameSpace = cl.getOptionValue("n", "test");
		as.put("namespace", nameSpace);
		String dataBase = cl.getOptionValue("db", "database");
		as.put("dataBase", dataBase);

		// start app
		SpringApplication.run(RecommendationService.class, args);

	}

}
