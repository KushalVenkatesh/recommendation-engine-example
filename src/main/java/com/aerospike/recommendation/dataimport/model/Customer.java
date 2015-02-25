package com.aerospike.recommendation.dataimport.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;

public class Customer implements IRecord {
	public static final String USERS_SET = "MOVIE_CUSTOMERS";
	public static final String CUSTOMER_ID = "customerID";
	public static final String WATCHED = "watched";
	public static final String RATINGS_COUNT = "ratings_count";
	String customerId;
	int ratingsCount = 0;
	List<WatchedRated> watched = null;

	public Customer(String customerId){
		super();
		this.customerId = customerId;
	}
	
	@SuppressWarnings("unchecked")
	public Customer(String customerId, Record record){
		this(customerId);
		fromRecord(record);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void fromRecord(Record record) {
		List<Map<String, Object>> list = (List<Map<String, Object>>) record.getValue(WATCHED); 
		this.ratingsCount = record.getInt(RATINGS_COUNT);
		this.watched = new ArrayList<WatchedRated>();
		if (list != null) {
			for (Map<String, Object> map : list){
				this.watched.add(new WatchedRated(map));
			}
		}
	}
	public List<WatchedRated> getWatched() {
		return watched;
	}
	public void setWatched(List<WatchedRated> watched) {
		this.watched = watched;
	}
	public String getCustomerId() {
		return customerId;
	}
	public int getRatingsCount() {
		return ratingsCount;
	}
	public void setRatingsCount(int count) {
		ratingsCount = count;
	}
	public int incrementCount() {
		return ++ratingsCount;
	}

	@Override
	public Bin[] asBins() {
		if (this.watched != null)
			return new Bin[] {Bin.asList("watched", this.watched)};
		else
			return new Bin[0];
	}

	@Override
	public Key getKey(String namespace, String set) throws AerospikeException {
		return new Key(namespace, set, this.customerId);
	}

}
