#Recommendation Algorithm

The customer ID supplied in the REST request is used as the key to retrieve the customer record.

Once we have the customer record, we make a vector from the list of movies watched.

This vector is simply a list of long integers. We will use this vector in our similarity comparisons.

We then iterate through the movies that the customer has watched, and build a list of customers that have watched these movies, and find the most similar customer using Cosine Similarity:

Having completed iterating through the list of similar customers you will have the customer with the highest similarity score. We then get the movies that this customer has watched 


