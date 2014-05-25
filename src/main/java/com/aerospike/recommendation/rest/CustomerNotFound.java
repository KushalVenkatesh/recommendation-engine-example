package com.aerospike.recommendation.rest;

public class CustomerNotFound extends RuntimeException {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2689850285266341615L;
	private String customerID;

	public CustomerNotFound() {
		super();
	}


	public CustomerNotFound(String user, Throwable cause) {
		super("Customer not found: " + user, cause);
		this.customerID = user;
	}


	public CustomerNotFound(String user) {
		super("Customer not found: " + user);
		this.customerID = user;
	}


	public String getCustomerID() {
		return customerID;
	}

	
}
