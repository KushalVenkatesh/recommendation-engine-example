package com.aerospike.recommendation.rest;

import java.util.List;

public class CosineSimilarity {

	/**
	 * Cosing similarity
	 * @param vec1
	 * @param vec2
	 * @return
	 */
	public static double cosineSimilarity(List<Long> vec1, List<Long> vec2) { 
		double dp = dotProduct(vec1, vec2); 
		double magnitudeA = magnitude(vec1); 
		double magnitudeB = magnitude(vec2); 
		return dp / magnitudeA * magnitudeB; 
	} 
	/**
	 * Magnitude
	 * @param vec
	 * @return
	 */
	public static double magnitude(List<Long> vec) { 
		double sum_mag = 0; 
		for(Long value : vec) { 
			sum_mag += value * value; 
		} 
		return Math.sqrt(sum_mag); 
	} 
	/**
	 * Dot product
	 * @param vec1
	 * @param vec2
	 * @return
	 */
	public static double dotProduct(List<Long> vec1, List<Long> vec2) { 
		double sum = 0; 
		if (vec1.size() > vec2.size()) {
			int diff = vec1.size() - vec2.size();
			for (int i = 0; i < diff; i++)
					vec2.add(0L);
			
		} else if (vec1.size() < vec2.size()) {
			int diff = vec2.size() - vec1.size();
			for (int i = 0; i < diff; i++)
					vec1.add(0L);
		}
		for(int i = 0; i<vec1.size(); i++) { 
			sum += vec1.get(i) * vec2.get(i); 
		} 
		return sum; 
	} 

}
