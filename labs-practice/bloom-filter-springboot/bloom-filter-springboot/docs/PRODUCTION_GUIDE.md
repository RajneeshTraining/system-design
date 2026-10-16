# Production Guide

This project is a faithful, working reference — the points below are what changes when you take it to a real
multi-instance system with billions of rows.

## 1. Multiple application instances

Each JVM currently holds its **own** in-memory filter (built from the same table, so identical in content).

| Option | Pros | Cons |
|---|---|---|
| **A. Per-instance filter + shared snapshot (this project)** | No extra infra, nanosecond lookups, no network hop | N × memory; instances can differ briefly after inserts handled by *another* instance |
| **B. Redis with RedisBloom (`BF.ADD`, `BF.EXISTS`)** | One shared filter, always consistent, instances stay small | Network hop per lookup (~0.2–1 ms), Redis becomes a dependency |
| **C. Redisson `RBloomFilter`** | Bloom stored in Redis as a bitmap; Java-friendly API | Same trade-offs as B; filter capacity is fixed at creation |

### Why A is usually fine for "email exists?" checks

An instance that has not yet seen a brand-new email (inserted via another instance) answers **"definitely not"** for it,
which is a **false negative** from the filter's perspective. To close that window:

* **Publish inserts** (Kafka / Redis pub-sub / Postgres `LISTEN/NOTIFY`) and have every instance call `bloom.put()`, **or**
* **Fall back on write path:** the sign-up flow already hits the DB unique index (this project does), so duplicates are
  still impossible; only the *read-only existence check* might be momentarily stale, **or**
* Use option B so all instances share one filter.

If a momentary "not found" for a just-created account is unacceptable (e.g. login right after sign-up across instances),
use B, or keep a small "recently created" Caffeine cache consulted before the filter.

### Swapping `EmailBloomFilter` for Redis

`UserService` only depends on `mightContain(String)` and `put(String,long)`. Extract an interface:

```java
public interface EmailMembership {
    boolean mightContain(String normalizedEmail);
    void put(String normalizedEmail, long id);
}
```

and add a Redis-backed implementation (RedisBloom `BF.EXISTS` / `BF.ADD`, or Redisson `RBloomFilter.contains/add`).
Keep the fail-open rule: if Redis is unreachable or the filter is not initialised, return `true`.

## 2. Deletes and email changes

Standard Bloom filters cannot remove elements. A deleted or changed email stays "maybe present" — a **false positive**,
which costs one DB lookup and still returns the correct answer. That is safe, but the filter's effective FPP drifts up.

Strategies:

1. **Periodic rebuild** (recommended): `POST /api/admin/bloom/rebuild` nightly/weekly from a replica. The rebuild
   swaps atomically while serving traffic.
2. **Counting Bloom filter** (4-bit counters, ~4× memory) — supports delete.
3. **Cuckoo filter** (supports delete, often smaller than a counting filter at low FPP) — e.g. RedisBloom `CF.*`.
4. **Generation filters:** keep "current" + "previous" filters and rotate.

## 3. Rolling deployments & snapshots

* Mount `bloom.snapshot-path` on a persistent volume; snapshots are written atomically (temp file + rename).
* On restart the snapshot is loaded and the app **re-scans from `highWaterMark − catch-up-safety-margin`**, so rows
  inserted while the pod was down are added.
* If you change `bloom.expected-insertions` or `bloom.fpp`, the old snapshot is ignored automatically and the filter
  is rebuilt.
* For fast scale-out, have one job build the snapshot and publish it to object storage (S3/GCS); new pods download it at start.

## 4. Database-side best practices at billions of rows

* Keep the **unique B-tree index on `email`** (`uk_users_email`). The filter reduces how often you use it; it never replaces it.
* Store emails normalized (lower-case, trimmed). Alternatively use a functional unique index on `lower(email)`.
* Consider **hash partitioning** by email (`PARTITION BY HASH (email)`) to keep each index partition manageable.
* Use a **read replica** for the filter build scan.
* Primary key `BIGSERIAL`/`BIGINT` monotonically increasing → keyset scan is cheap. If your PK is a random UUID, add a
  monotonic column (or use `created_at, id`) for the scan cursor.
* The scan cursor assumes ids become visible roughly in order; the safety margin covers small out-of-order commits.
  Increase it for very high concurrency, or run a nightly rebuild.

## 5. Security & privacy

* The filter holds hashes in a bit array — it cannot be reversed into emails. A snapshot file is far less sensitive than a table dump.
* **User enumeration:** an API answering "email exists / not" can be abused. Rate-limit and return uniform responses on login/reset flows. A Bloom filter makes enumeration cheaper for *you*, not for the attacker, but the information leak is the endpoint's, not the filter's.
* Protect `/api/admin/**` (Spring Security, mTLS, or internal-only ingress).

## 6. Observability

Micrometer counters (exposed at `/actuator/metrics/bloom.lookup`):

| Tag `result` | Meaning |
|---|---|
| `definitely_absent` | Filter rejected → **no DB call** |
| `db_hit` | Filter said maybe, DB found the row |
| `false_positive` | Filter said maybe, DB had nothing |

Alerts worth setting:

* Observed FPP (`false_positive / (false_positive + definitely_absent)`) > 2 × configured `fpp` → filter is over capacity or stale; rebuild/resize.
* Filter not ready for > N minutes after startup.
* Snapshot age too old.
* Share of DB calls avoided falling suddenly (traffic pattern changed — mostly hits now?).

`GET /api/admin/bloom/stats` returns all of the above in one JSON.

## 7. Load testing checklist

1. Seed realistic volume on a staging DB (use `DEMO_ROWS`, or restore a sanitized copy).
2. Send a mix, e.g. 80 % unknown emails / 20 % known; compare DB QPS and p99 latency with `bloom.fpp` and with the filter disabled (`bloom.expected-insertions` tiny is *not* a disable — instead temporarily return `true` from `mightContain`).
3. Verify correctness under rebuild: run `/rebuild` during load; no 404 for known emails.
4. Kill -9 a pod; confirm it restarts from snapshot and serves correctly while catching up.

## 8. Alternatives worth knowing

| Need | Consider |
|---|---|
| Deletes | Cuckoo filter / counting Bloom filter |
| Shared filter across fleet | RedisBloom / Redisson |
| Exact existence answer for hot subset | Caffeine cache of recent hits in front of the filter |
| Range/prefix queries | Not a Bloom filter use case (use the DB index / search engine) |
| Slimmer than Bloom (static sets) | Xor / Binary Fuse filters (immutable) |
