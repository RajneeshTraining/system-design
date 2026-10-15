# Part 8: Comparison and Decision Guide

*Oct 15, 2026 · Part 8 of 8 · [Series outline](series-outline.md) · [Previous: Part 7](part-7-protecting-email-pii.md)*

Start with the simplest option that meets your scale, and move down the list only when measurements show you need to.

## Side-by-side comparison

| Option | Best query type | Scale | Effort to adopt | Operational burden | Main trade-off |
| --- | --- | --- | --- | --- | --- |
| [B-Tree index (Part 2)](part-2-btree-index.md) | Exact, prefix, range | Up to a few billion rows on one big server | Low | Low | Single-machine ceiling |
| [Partition or shard (Part 3)](part-3-partitioning-and-sharding.md) | Exact by shard key | Very large, growing | Medium | Medium to high | Cross-shard queries are hard |
| [Key-value NoSQL (Part 4)](part-4-key-value-nosql.md) | Exact by key | Very large, very high traffic | Medium to high | Low if managed | Weak ad-hoc queries |
| [Search engine (Part 5)](part-5-search-engines.md) | Partial, fuzzy, multi-field | Very large | Medium | Medium | Second system, eventual consistency |
| [Cache and Bloom filter (Part 6)](part-6-cache-and-bloom-filter.md) | Repeats and misses | Add-on | Low | Low | Stale data, false positives |
| [Blind index (Part 7)](part-7-protecting-email-pii.md) | Exact on protected data | Add-on | Low to medium | Key management | No partial search |

"A few billion rows on one server" is a rule of thumb, not a limit: it depends on row size, memory, disks and the workload, so test with your own data.

## How to choose

Answer the questions in order. Stop at the first outcome that fits.

![Decision flow: need only exact match? If not, a search engine. If yes, does it fit on one strong server? If yes, a B-Tree index. If not, can you change database? If yes, key-value NoSQL, if not, partition or shard. Then add a cache and Bloom filter for repeats and misses, and a blind index for personal data](images/decision-flow.svg)

In words:

1. **Do you need more than exact match?** (partial email, domain search, typos) Use a **search engine** (Part 5), fed from your main database.
2. **Exact match only, and the data fits on one strong server?** Use a **B-Tree index** (Part 2).
3. **Exact match, but one server is too small, and you want to keep SQL?** **Partition or shard** (Part 3).
4. **Exact match at huge scale, simple access pattern, and you can change database?** **Key-value NoSQL** (Part 4).
5. **Many repeat lookups, or many lookups for emails that do not exist?** Add a **cache and Bloom filter** (Part 6) in front.
6. **Is the email sensitive personal data?** Add the **blind index** (Part 7) on top of whichever store you chose.

## What this looks like as you grow

| Stage | What you run | Move to the next stage when |
| --- | --- | --- |
| 1. Start | One relational database with a B-Tree index on email | The hot part of the index no longer fits in memory, or one server cannot keep up |
| 2. Add speed | Stage 1 plus a cache, and a Bloom filter if many lookups are misses | The database is still the bottleneck after caching |
| 3. Scale out | Partitioned or sharded SQL, or a key-value store as the source of truth, with the cache in front | The product needs partial or fuzzy search |
| 4. Add search | Stage 3 plus a search engine fed by change data capture | n/a |
| Any stage | Blind index on the email column if it is protected data | n/a |

Most systems never need stage 4, and many never need stage 3.

## A typical production stack

A sharded relational database (or DynamoDB) as the source of truth, a Redis cache in front, a Bloom filter for sign-up checks, a blind index for protected emails, and Elasticsearch only if the product needs flexible search.

## Before you commit: a checklist

1. **Normalise emails** (trim, lower-case) on write and on search, in every layer.
2. **Test with realistic volume**, not a sample of a million rows.
3. **Check p95 and p99 latency**, not only the average.
4. **Confirm the plan.** Use `EXPLAIN` (or the engine's profiler) to prove the lookup uses the index, key or shard you expect.
5. **Watch the right metrics:** index size vs memory, cache hit ratio, shard size skew, replication lag, search index lag.
6. **Plan backups and restores** for every store, and test a restore.
7. **Protect personal data:** encryption, key management, access control, audit logs, retention and deletion.
8. **Write down the migration path** before you need it (for example dual-write, backfill, compare, switch).

## Series recap

| Part | One-line takeaway |
| --- | --- |
| [1](part-1-why-searching-billions-of-rows-is-hard.md) | Without an index, a lookup reads every row; with one it takes a few dozen steps |
| [2](part-2-btree-index.md) | A B-Tree index is the simplest fix and enough for many systems |
| [3](part-3-partitioning-and-sharding.md) | Hash the email to split data so each lookup touches one small piece |
| [4](part-4-key-value-nosql.md) | A key-value store routes by key for you, at the cost of flexible queries |
| [5](part-5-search-engines.md) | A search engine adds partial and fuzzy search, as a synced copy |
| [6](part-6-cache-and-bloom-filter.md) | A cache and a Bloom filter answer repeats and misses without the database |
| [7](part-7-protecting-email-pii.md) | A keyed-hash blind index lets you search without storing plain emails |
| 8 | Pick the simplest option that fits, measure, and add layers only when needed |
