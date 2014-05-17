#Building a Recommendation Engine with Aerospike and Spring Boot 

Author: Peter Milne, Aerospike Director of Application Engineering

**Recommendation engines** are used in applications to personalize the user experience. For example, e-commerce applications recommend products to a customer that other customers -  with similar behavior - have viewed, enjoyed, or purchased. News application would use a real-time recommendation engine, as stories come and go quickly. These application additions improves the user experience, increases sales and helps retain loyal customers. This guide contains code for an example real-time non-contextual cosine-similarity based engine. By using most recent behavior for recommendations, etra 

This example use the **Spring Boot** application environment, a powerful jump-start into the versatile Spring Java based web application framework. With Spring Boot you can build powerful applications with production grade services with little effort - and easily launch and enjoy the enclosed example. This example can be translated into other frameworks.

The **Aerospike** database is used as the storage engine, and is a good fit. Aerospike is a highly available, low latency NoSQL database that scales linearly - thus easy to run an online service. It is an in-memory database optimized to use both DRAM and native Flash. Aerospike boasts latencies averaging less than 1 millisecond with well more than 100,000 queries per second per server, with high availability and immediate consistency. Aerospike is well suited to this example because in order to make a recommendation, thousands of objects and behaviors may be examined, and each behavior will generate two writes to the database.
##What you will build
This guide will take you through accessing the Github repository containing the project, and creating a simple recommendation service. The provided engine will use Similarity Vectors to recommend a product - in the case of the example data set, movies - to a customer. The algorithm for this is very elementary, and will provide a starting point for real-time recommendation research, but also will provide recommendations based on the demonstration data provided.

To provide a recommendation in real-time, you will need a database that can retrieve your data very quickly, because several database requests will be necessary to do the full recommendation. If your database is too slow, you will easily find - even over reasonable data sets - that the recommendation time is slow. You could try this with any database, but we recommend the Aerospike NoSQL database - it is very fast (1- 5ms latency per request) and the latency remains flat as the transaction rate grows.

You will build a service that accepts an HTTP GET request:

	http://localhost:8080/recommmendation/{user}

It responds with the following JSON array of recommendations:
```json
[
  {"expiration":130019315,
   "bins":
     {"title":"Classic Albums: Meat Loaf: Bat Out of Hell",  "yearOfRelease":"1999"},
   "generation":4},
  {"expiration":130019337,
   "bins":
     {"title":"Rudolph the Red-Nosed Reindeer",
"yearOfRelease":"1964"},
   "generation":4},
  {"expiration":130019338,
   "bins":{"title":"The Bad and the Beautiful",
    "yearOfRelease":"1952"},
   "generation":4},
  {"expiration":130019384,
   "bins":
    {"title":"Jingle All the Way","yearOfRelease":"1996"},
   "generation":4},
  {"expiration":130019386,
   "bins":
     {"title":"The Killing","yearOfRelease":"1956"},
  "generation":4},
  {"expiration":130019400,
 "bins":
  {"title":"Silkwood","yearOfRelease":"1983"},"generation":4},
  {"expiration":130019404,"bins":{"title":"Chain of Command","yearOfRelease":"2000"},
   "generation":4}]
```
Each element in the JSON array contains 3 fields: generation, expiration, bins. Generation is the generation number use by Aerospike for concurrency. Expiration is the date the record will automatically expire, measured in seconds from January 01, 2010 GMT. Bins is list of name/value pairs that are the data of the record. Bins are similar to fields in Aerospike. 


If you’d like to just jump into trying out the code, skip forward to the “Setup the project” section.

There are also many features added to your application out-of-the-box for managing the service in a production (or other) environment. This functionality comes from Spring, see the Spring guide: [Building a RESTful web service](http://spring.io/guides/gs/rest-service).

##Recommendation Algorithm

This is a non-contextual behavioral recommendation engine. There are three categories of objects: Customers, Ratings, and Movies. Customers are identified by randomized identifiers. There are a variety of actions possible - like viewing a movie, or rating a movie. Like the customer identifier, the Movie identifier is abstract.

In our provided example, customers watch and rate movies. Based on their reviews, other customers determine whether they are interested in watching particular movies. Although our example uses this exact data set, it should be clear how to adapt this code to other data models.

A customer’s profile will have a history of their views and ratings; and a Movie will have a history of people who have rated it.

A simple recommendation task is to find another customer who is similar to the target customer and recommend products that the other customer has enjoyed. It is a good idea to eliminate the duplicates so that the target user is only recommended movies that they have not seen.

The data we are using in this exercise is a simulated data set which lists Movies watched by Customers, similar to data that any media site like NetFlix, Hulu, or Tivo would use. In this simulation, there is only about 25 movies in the data set. The data is deliberately sparse to reduce the size, consequently data integrity is not guaranteed. There will be many more users and ratings than movies and some movies have no ratings at all.

##Schema
Aerospike has a flexible NoSQL data model. A Set can be used to group records, like a relational database table, but no restrictions are applied to the records in a set.  

The data is this example is stored in two Aerospike Sets: 
- MOVIE_TITLES
- MOVIE_CUSTOMERS

###Ratings
A rating is a sub-record that stores map of values. It is stored in a Large List. The large list is stored in a Bin in both the Customer record and the Movie record. The values in the map are:

customer-id | movie-id | rating | date
------------|----------|--------|-----
String | String | Integer | String

###Movies
The Movie record consists of some details about the movie e.g. Title and Year of release, but more importantly a list of who has watched it, the users rating and when it was rated. This list is important in determining who is the most similar customer.


Move ID (primary key) | YearOfRelease | Title | WATCHED_BY | Rating
----------------------|---------------|-------|------------|-------
String | String | String | Large List of Ratings | Integer

###Customers
The Customer record has a customer ID and a List of movies watched and rated. It could contain additional attributes about the customer, but for this example it simply contains a list of ratings.

Customer ID (primary key) | MOVIES_WATCHED
--------------------------|---------------
String | Large List of Ratings

##How do you find similarity?
Similarity can be found using several algorithms, there are many academic papers available that describe the high order Mathematics on how to do this. In this example, you will use a very simple algorithm using Cosine Similarity to produce a simple score.

###Scenario
1. Jane Doe accesses the application
2. Retrieve Jane’s User Profile
3. Retrieve the Movie record for each movie that Jane has watched. If the number of movies is small, you can use a batch operation in Aerospike that retrieves a list of records in one lump. If it is large, it is better to retrieve them in spurts.
4. For each movie:
 - Retrieve each of the watched user profiles
 - See if this profile is similar to Jane’s by giving it a score
5. Using the user profile with the highest similarity score, recommend the movies in this user profile that Jane has not seen.

This is a **very elementary** technique and it is useful only as an illustration, and it does have several flaws. Here are a few:
 - Imagine that Jane has watched Harry Potter. It would be foolish to calculate similarity using the customer profiles who viewed this movie, because a very large number of people watched Harry Potter. If we generalize this idea, it would be that movies with the number of views over a certain threshold should be excluded.
 - Cosine similarity assumes each element in the vector has the same weight. The elements in our vectors are the movie IDs, but we also have the rating of the movie also. A better similarity algorithm would include both the movie ID and its rating.

##What you will need
 - About 45 minutes
 - A favorite text editor or IDE
 - [JDK 7](http://www.google.com/url?q=http%3A%2F%2Fwww.oracle.com%2Ftechnetwork%2Fjava%2Fjavase%2Fdownloads%2Findex.html&sa=D&sntz=1&usg=AFQjCNGWCcKCIFm3bfDWtU41j6HJzekqNQ) or later
 - [Maven 3.0+](http://maven.apache.org/download.cgi)
 - [Aerospike Java SDK 3.0+](http://www.aerospike.com/aerospike-3-client-sdk/)
 - An Aerospike 3 server installation
 - The test data


##Setup the project
As this project is written in Java and using the Spring framework with Aerospike, you need Java and the Aerospike Java client installed. We use [Maven](https://www.google.com/url?q=https%3A%2F%2Fmaven.apache.org%2F&sa=D&sntz=1&usg=AFQjCNEeDpKe4dgXLJx95yPQ8bGMJ9HR-A) to build the project, which also must be installed. If you are unfamiliar with Maven refer to the Spring guide: [Building Java Projects with Maven](http://spring.io/guides/gs/maven).
###Step 1: Install Maven and the Aerospike Java client

Follow the instructions to [Install Maven](http://maven.apache.org/guides/getting-started/maven-in-five-minutes.html) your development machine.

You will also need to build and install the [Aerospike Java client](https://docs.aerospike.com/display/V3/Java+Client+-+Installation) into your local Maven repository.  

###Step 2: Clone the project git repository

All the source code for this example is a GitHub [here](https://github.com/aerospike/recommendation-engine-example). To clone the repository to your development machine, enter the following command:
```bash
git clone https://github.com/aerospike/recommendation-engine-example
```
###Step 3: Install Aerospike and load the test data
The test data for this application is stored as an Aerospike database file. Rather than going through the process of uploading data from a CSV file, I created a “single node” Aerospike cluster and loaded the data, then saved a copy of the database file. This means that you can start with a fully loaded database.

Setting up an Aerospike single node cluster is easy. Aerospike only runs on Linux, so to develop on my Mac I use one or more virtual machines. I use VMware Fusion, but you can just as easily use Open Virtual Machine Tools, or your favorite VM software.

Create a single Linux VM (I use CentOS)

Install Aerospike 3 using the instructions [Install a Single Node](https://docs.aerospike.com/display/V3/Install+a+Single+Node) at the Aerospike web site.

***RE WRITE THIS***
Download the file movie-data.zip to the node that you have installed Aerospike. You can also use the command: 
```bash
wget https://docs.google.com/uc?export=download&id=0B8luCpttpeaAOGtMUUhUbThXSFU
```
Un-tar the archive with 
```bash
tar -xvf movie-data.zip
```
and you will find two files:
aerospike.conf
test.data

Be sure that Aerospike is not running by entering the command on your Linux machine:
```bash
sudo /etc/init.d/aerospike stop
```
Copy the file: aerospike.conf to /etc/aerospike/aerospike.conf on the Linux machine that you have installed your single node cluster.

Copy the file: test.data to /var/data/serospike/test.data on your Linux machine. This is the database file containing the data. The aerospike.conf file is a configuration file that defines a namespace called “test” that persists its data in /var/data/aerospike/test.data.

###Step 4: Start Aerospike and confirm the test data is loaded
Start Aerospike with:
```bash
sudo /etc/init.d/aerospike  start
```
Confirm that Aerospike started OK with
```bash
sudo /etc/init.d/aerospike status
```
Confirm that the data is in place by using the Aerospike AQL utility. AQL is a SQL-like language that allows you to declaratively manipulate data in Aerospike 3.
```bash
sudo aql
```
At the AQL prompt:
```sql
show sets
```
The output will look like this:
```sql
aql> show sets
+-----------+----------------+----------------------+---------+-------------------+------------+---------------------+
| n_objects | set-enable-xdr | set-stop-write-count | ns_name | set_name          | set-delete | set-evict-hwm-count |
+-----------+----------------+----------------------+---------+-------------------+------------+---------------------+
| 24        | "use-default"  | 0                    | "test"  | "MOVIE_TITLES"    | "false"    | 0                   |
| 36877     | "use-default"  | 0                    | "test"  | "MOVIE_CUSTOMERS" | "false"    | 0                   |
+-----------+----------------+----------------------+---------+-------------------+------------+---------------------+
2 rows in set (0.001 secs)
```
