# Searching Billions of Records by Email: A Beginner's Blog Series

*Oct 13, 2026 · Series outline · each part below has a full post with diagrams*

To find one email among billions of rows in milliseconds, never scan the table: build a lookup structure on email (an index, a key, or a cache) and pick the option that matches your scale, budget and team.

## The posts

| Part | Post |
| --- | --- |
| 1 | [Why searching billions of rows by email is hard](part-1-why-searching-billions-of-rows-is-hard.md) |
| 2 | [B-Tree index in a relational database](part-2-btree-index.md) |
| 3 | [Partitioning and sharding](part-3-partitioning-and-sharding.md) |
| 4 | [Key-value and NoSQL stores](part-4-key-value-nosql.md) |
| 5 | [Search engines (Elasticsearch / OpenSearch)](part-5-search-engines.md) |
| 6 | [Speed boosters: cache and Bloom filter](part-6-cache-and-bloom-filter.md) |
| 7 | [Protecting email data (blind index)](part-7-protecting-email-pii.md) |
| 8 | [Comparison and decision guide](part-8-comparison-and-decision-guide.md) |

The sections below are the short summary of each part.

## Series map

The series has eight parts: the problem, four ways to store and find the row, two speed boosters, protection for personal data, and a decision guide.

![Lookup flow: the app asks the cache, then the Bloom filter, then the primary store (B-Tree index, sharded SQL or key-value NoSQL); a search engine is an optional side path kept in sync from the primary store; Part 7 keeps a keyed hash of the email in every store](images/series-map.svg)

Read the picture left to right: the app asks the cache, then the Bloom filter, then the primary store you chose. A search engine is an optional side path for partial or fuzzy search, kept in sync from the primary store.

## Part 1 – The problem: why a full scan fails

Without a lookup structure, the database reads every row to find one email, so the work grows with the table: billions of rows means minutes, not milliseconds.

**The example.** A `customers` table with `id`, `name`, `email`, `phone`, `city`. The query is:

```sql
SELECT * FROM customers WHERE email = 'asha@example.com';
```

**Three words to know first:**

1. **Full scan** – the database opens every row and compares the email. Cost grows linearly with row count.
2. **Index** – a small, sorted or hashed side-structure that maps `email → row location`, like a book's index.
3. **Lookup** – using that structure to jump straight to the row. Cost grows with the *logarithm* of the row count (or is constant), so 1 billion rows needs only a few dozen steps.

**What every option in this series does:** it gives you a faster way to answer "where is this email?". They differ in where the lookup structure lives, how much it costs and how much your team has to operate.

**Rule of thumb for the whole series:** an exact match on email (`=`) is the easy case. Partial or fuzzy search (`LIKE '%asha%'`, typos) is a different, harder problem, covered in Part 5.

## Part 2 – B-Tree index in a relational database

A B-Tree index on `email` is the simplest fix: one SQL statement, no new system, and lookups touch only a handful of pages even at a billion rows.

**How it works.** The database keeps emails in a sorted tree. It starts at the root, picks the right branch at each level, and reaches the leaf that holds the row pointer. A tree 4 to 5 levels deep covers billions of rows.

**Steps to implement (PostgreSQL shown; MySQL is similar):**

1. Create the index without blocking writes:

```sql
CREATE UNIQUE INDEX CONCURRENTLY idx_customers_email ON customers (email);
```

2. Normalise emails before storing (lower-case, trimmed) so `Asha@X.com` and `asha@x.com` match. A common pattern is an index on `lower(email)`.
3. Check it is used:

```sql
EXPLAIN SELECT * FROM customers WHERE email = 'asha@example.com';
```

Look for `Index Scan` instead of `Seq Scan`.

4. Return only the columns you need, so the database can answer from the index alone (a *covering index*).

| Pros | Cons |
| --- | --- |
| Easiest to add; works with the SQL you already have | A single server still holds one huge index (often tens of GB per billion rows) |
| Also supports range and prefix queries (`email LIKE 'asha%'`) | Index build and maintenance (vacuum, rebuild) get slow on very large tables |
| Strong consistency and transactions | Every insert also updates the index, so write-heavy loads slow down |
| Mature tooling and monitoring | Hits a ceiling on one machine; then you need Part 3 |

**Best for:** up to a few billion rows on well-sized hardware, when you want the least operational change.

## Part 3 – Partitioning and sharding

When one index is too big for one server, split the table into smaller pieces by a rule on email, so each lookup touches only one small piece.

**Two flavours, easy to confuse:**

1. **Partitioning** – one database server, the table split into smaller child tables. Fixes index size and maintenance time.
2. **Sharding** – many database servers, each owning a slice of the data. Fixes size *and* throughput.

**The routing rule.** Compute a hash of the email and take it modulo the number of shards: `shard = hash(email) % N`. The same email always goes to the same shard, so the app (or a router) knows exactly where to look. Avoid splitting by first letter, because some letters are far more common and one shard becomes a hotspot.

**Steps to implement:**

1. Pick the shard key: `email` (normalised). Every lookup by email now hits exactly one shard.
2. Choose a tool: native partitioning (PostgreSQL `PARTITION BY HASH (email)`), or a sharding layer (Citus for PostgreSQL, Vitess for MySQL).
3. Create a B-Tree index on email inside each partition or shard (Part 2).
4. Plan to grow: use consistent hashing or many small logical shards, so adding servers moves only a fraction of the data.
5. Decide how to look up by other fields (phone, id). Those queries either need their own lookup table or fan out to every shard.

| Pros | Cons |
| --- | --- |
| Scales storage and traffic by adding servers | More moving parts: routing, rebalancing, backups per shard |
| Smaller indexes mean faster lookups and maintenance | Queries by anything other than the shard key hit all shards |
| Keeps SQL and most of your existing tooling | Cross-shard joins and transactions are hard or slow |
| Failure affects only one shard | Changing the shard key later is a major migration |

**Best for:** teams that want to stay on SQL but have outgrown one machine.

## Part 4 – Key-value and NoSQL stores

In a distributed key-value store such as DynamoDB, Cassandra or ScyllaDB, email is the partition key, so every lookup goes straight to the right node with no index search at all.

**How it works.** The store hashes the key, finds the node that owns that hash range, and reads the row there. Sharding, replication and rebalancing are built in, so you do not write the routing yourself.

**Steps to implement:**

1. Design the table around the query: partition key = normalised email, other fields = attributes.
2. Pick a managed service (DynamoDB, Astra, Keyspaces) or self-host (Cassandra, ScyllaDB).
3. Write with a clear consistency level. Strongly consistent reads are slower; eventually consistent reads are cheaper and faster.
4. For any other search field (phone, id), add a second table or a secondary index keyed by that field.
5. Keep each item small; store large blobs elsewhere.

| Pros | Cons |
| --- | --- |
| Predictable single-digit-millisecond lookups at very large scale | Only fast for the key you designed for; ad-hoc queries are weak |
| Scaling out and replication are largely automatic | Limited joins and transactions compared with SQL |
| Managed options remove most operations work | Needs a different data-modelling mindset and a data migration |
| Handles very high read and write rates | Cost can grow quickly on managed services at high throughput |

**Best for:** very large, read-heavy lookup by a known key, with a team ready to move off a relational model.

## Part 5 – Search engines (Elasticsearch / OpenSearch)

Choose a search engine when people need more than an exact match: partial email, domain search (`@gmail.com`), typos or filters combined with other fields.

**How it works.** The engine builds an *inverted index*: for each term (a word or a piece of an email) it stores the list of documents containing it. The index is split into shards spread across nodes, and a query is answered in parallel.

**Important design rule.** Keep your main database as the *source of truth* and treat the search engine as a read-optimised copy. Sync changes through a queue or change-data-capture.

**Steps to implement:**

1. Map `email` as a `keyword` field for exact match. For partial search add an `ngram` or `wildcard` sub-field.
2. Index records in bulk from your database, then keep them in sync via CDC (for example Debezium and Kafka).
3. Query with a `term` filter for exact lookups; it skips scoring and is fast.
4. Size shards sensibly (tens of GB each) and add replicas for availability.
5. Fetch full details from the main database by id if you do not store everything in the index.

| Pros | Cons |
| --- | --- |
| Exact, prefix, partial and fuzzy search in one place | A second system to run, monitor and pay for |
| Scales out across nodes; fast filtering and aggregations | Data is eventually consistent with the main database |
| Handles combined filters (email + city + date) | Indexing partial-match fields uses a lot of storage |
| Rich ecosystem and managed offerings | Not a primary database; reindexing large data takes time |

**Best for:** flexible search experiences. For a plain exact match on email it is usually more than you need.

## Part 6 – Speed boosters: cache and Bloom filter

A cache and a Bloom filter do not replace the options above; placed in front of them, they answer repeat and "not found" lookups without touching the big database.

**1. Cache (Redis, Memcached).** Keep recently looked-up emails in memory.

- Flow: check the cache first. On a hit, return it. On a miss, read the database, store the result in the cache with an expiry (TTL), then return it.
- Watch out for stale data: delete or update the cache entry whenever the record changes.

**2. Bloom filter.** A tiny in-memory structure that answers "is this email definitely NOT in the database?" with no false negatives. It can sometimes say "maybe present" for an email that is absent (a false positive), so a "maybe" still goes to the database.

- Why it helps: lookups for emails that do not exist (typos, bots, sign-up checks) are rejected instantly and never reach the database.
- Redis offers Bloom filters through its probabilistic module; many databases such as Cassandra and HBase use them internally.
- Rebuild or grow it as data grows, and accept that deleting entries needs a counting variant.

| | Cache | Bloom filter |
| --- | --- | --- |
| Answers | The actual record | "Definitely not here" or "maybe here" |
| Helps most with | Repeated lookups of the same email | Lookups for emails that do not exist |
| Main risk | Stale data | False positives; hard to delete |
| Memory use | Grows with cached records | Very small, fixed by design |

**Best for:** any option above. Add them once you measure that the main store is the bottleneck.

## Part 7 – Protecting PII: search on encrypted or hashed emails

Email is personal data, so store it encrypted and search it through a keyed hash (a *blind index*), which lets you do exact lookups without keeping plain emails in the index.

**The problem.** Encrypted values change every time they are encrypted, so you cannot index or search them directly.

**The blind-index pattern:**

1. Normalise the email (trim, lower-case).
2. Compute `HMAC-SHA256(secret_key, email)` and store it in a column such as `email_hash`.
3. Encrypt the real email separately (AES-GCM, with keys in a KMS or HSM) and store it in `email_enc`.
4. Index `email_hash` using any option from Parts 2 to 4.
5. To search, compute the same HMAC for the input and look it up; decrypt the matching row only when the app needs to show it.

**Rules to follow:**

- Use a keyed HMAC, not a plain hash. Plain SHA-256 of emails can be reversed by guessing common addresses.
- Keep the HMAC secret in a key-management service and plan for key rotation.
- Only exact match works this way. Partial or fuzzy search on protected data needs extra design, so decide early whether you need it.
- Limit access, log reads and follow your privacy laws (for example GDPR or India's DPDP Act) when storing personal data.

| Pros | Cons |
| --- | --- |
| Fast exact lookups with no plain email in the index | No partial or range search on the hash |
| Limits damage if the index or a backup leaks | Key management and rotation add work |
| Works with every database in this series | A hash of the same email is identical, so it reveals duplicates |

## Part 8 – Comparison and decision guide

Start with the simplest option that meets your scale, and move down the table only when measurements show you need to.

| Option | Best query type | Scale | Effort to adopt | Operational burden | Main trade-off |
| --- | --- | --- | --- | --- | --- |
| B-Tree index (Part 2) | Exact, prefix, range | Up to a few billion rows on one big server | Low | Low | Single-machine ceiling |
| Partition or shard (Part 3) | Exact by shard key | Very large, growing | Medium | Medium to high | Cross-shard queries are hard |
| Key-value NoSQL (Part 4) | Exact by key | Very large, very high traffic | Medium to high | Low if managed | Weak ad-hoc queries |
| Search engine (Part 5) | Partial, fuzzy, multi-field | Very large | Medium | Medium | Second system, eventual consistency |
| Cache and Bloom filter (Part 6) | Repeats and misses | Add-on | Low | Low | Stale data, false positives |
| Blind index (Part 7) | Exact on protected data | Add-on | Low to medium | Key management | No partial search |

**Quick decision guide:**

1. Only exact email match, data fits one strong server → **B-Tree index** (Part 2).
2. Exact match, one server is too small, you want to keep SQL → **Partition or shard** (Part 3).
3. Exact match, huge scale, simple access pattern, willing to change database → **Key-value NoSQL** (Part 4).
4. Need partial, domain or fuzzy search → **Search engine** (Part 5) fed from your main database.
5. Too many repeat reads or lookups for emails that do not exist → add **cache and Bloom filter** (Part 6).
6. Email is sensitive personal data → add the **blind index** (Part 7) on top of whichever you chose.

**A typical production stack:** a sharded relational database or DynamoDB as the source of truth, a Redis cache in front, a Bloom filter for sign-up checks, a blind index for protected emails, and Elasticsearch only if the product needs flexible search.

**Before you commit, measure:** test with realistic data volume, check the p95 and p99 latency of the lookup, and confirm the plan with `EXPLAIN` or the engine's profiler.
