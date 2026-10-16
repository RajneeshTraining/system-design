# Searching Billions of Records by Email

A beginner-friendly blog series on how to find one record by email in a table with billions of rows: the options, their pros and cons, step-by-step implementation, and diagrams.

## Posts

| Part | Post | What you learn |
| --- | --- | --- |
| 1 | [Why searching billions of rows by email is hard](part-1-why-searching-billions-of-rows-is-hard.md) | Full scan vs index, the numbers, a hands-on PostgreSQL experiment |
| 2 | [B-Tree index in a relational database](part-2-btree-index.md) | The simplest fix, for PostgreSQL and MySQL |
| 3 | [Partitioning and sharding](part-3-partitioning-and-sharding.md) | Splitting the table when one server is not enough |
| 4 | [Key-value and NoSQL stores](part-4-key-value-nosql.md) | DynamoDB, Cassandra and ScyllaDB with email as the key |
| 5 | [Search engines (Elasticsearch / OpenSearch)](part-5-search-engines.md) | Partial, domain and fuzzy email search |
| 6 | [Speed boosters: cache and Bloom filter](part-6-cache-and-bloom-filter.md) | Answering repeats and misses without the database |
| 7 | [Protecting email data (blind index)](part-7-protecting-email-pii.md) | Searching without storing plain emails |
| 8 | [Comparison and decision guide](part-8-comparison-and-decision-guide.md) | Choosing and combining the options |

[series-outline.md](series-outline.md) has a one-page summary of the whole series.

## Images

All diagrams are SVG files in [images/](images) and are referenced from the posts with relative paths, so keep the folder structure when you publish.
