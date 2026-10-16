# Sizing a Bloom Filter for Billions of Emails

## Formulas

```
bits per element  = -ln(p) / (ln 2)²   ≈ 1.44 · log2(1/p)
total bits  m     = n · bits per element
memory (bytes)    = m / 8
hash functions k  = bits per element · ln 2
```

`n` = number of emails you plan to hold (**size for future growth**), `p` = target false-positive probability.

## Reference table (memory is heap, held in a `long[]`)

| Emails (n) | p = 1 % (k=7) | p = 0.1 % (k=10) | p = 0.01 % (k=13) |
|---|---|---|---|
| 10 million | 12 MB | 18 MB | 24 MB |
| 100 million | 120 MB | 180 MB | 240 MB |
| 1 billion | 1.2 GB | 1.8 GB | 2.4 GB |
| 5 billion | 6.0 GB | 9.0 GB | 12 GB |

Measured with this project's Guava filter: **1,000,000 emails at 1 % → 1,198,170 bytes**, observed false-positive rate **1.005 %**.

## Choosing p

The cost of a false positive is one ordinary indexed DB lookup — the same cost you pay today for every miss.

* `p = 1 %` → 99 % of misses are free. Usually the sweet spot.
* `p = 0.1 %` → +50 % memory for 10× fewer wasted DB calls. Worth it if the DB is the bottleneck and misses dominate.
* Going below 0.1 % rarely pays off.

Rule of thumb: pick the largest `p` for which `miss_rate × p × lookups/sec` is comfortably below what the DB can absorb.

## Choosing n (capacity)

* Use `current rows × growth over the filter's lifetime` (e.g. 1.5× for 12–18 months).
* Check `GET /api/admin/bloom/stats` → `currentExpectedFpp`. If it exceeds ~2× your target, schedule a rebuild with a larger `bloom.expected-insertions`.
* Changing `expected-insertions` or `fpp` invalidates the snapshot automatically (the app detects the mismatch and rebuilds).

## JVM settings

Guava stores the bits on-heap in one large `long[]`. A 1.2 GB array is a G1 "humongous" allocation:

```
-Xms4g -Xmx6g -XX:+UseG1GC -XX:G1HeapRegionSize=32m
```

Rule of thumb: heap ≥ filter size + 2–3 GB working headroom. Guava supports filters up to roughly 137 billion bits per
instance (2^31 longs); for larger sets shard by hash (e.g. 16 filters keyed by `hash(email) % 16`).

## Build time (one-off, then snapshots)

Scanning the table is I/O-bound: keyset pagination on the primary key streams rows sequentially. As an **order-of-magnitude
estimate only** (hardware dependent — measure on your replica), expect on the order of 100k–500k rows/second, i.e. roughly
1–3 hours for a billion rows. Do this on a **read replica**, and rely on the snapshot afterwards:

* Startup with a snapshot: load a ~1.2 GB file (seconds to a minute) + scan only rows newer than the saved high-water id.
* Without a snapshot: full scan; during that time lookups simply go to the DB (fail-open).

## Disk for snapshots

Snapshot size ≈ filter size (+ 28 bytes of header). Keep it on a persistent volume (Kubernetes PVC) or object store
and ship it to new instances at start-up to avoid every pod rescanning the table.
