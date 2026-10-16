# Bloom Filter for Email Lookups (Spring Boot)

A reference implementation showing how to put a **Bloom filter in front of a very large `users` table**
(hundreds of millions to billions of rows) so that lookups by **email** for non-existent users never reach the database.

```
GET /api/users?email=x@y.com
        │
        ▼
 ┌───────────────┐  "definitely NOT present" (≈ 99%+ of misses)   ┌──────────────┐
 │ Bloom filter  │ ─────────────────────────────────────────────► │ 404, no DB   │
 │ (in memory)   │                                                 └──────────────┘
 └──────┬────────┘
        │ "maybe present"
        ▼
 ┌───────────────┐   found → 200        not found (false positive) → 404
 │ PostgreSQL    │
 │ unique index  │
 └───────────────┘
```

> New to Bloom filters? Read [`docs/BLOOM_FILTER_EXPLAINED.md`](docs/BLOOM_FILTER_EXPLAINED.md) first (10 min).

## Contents

| Path | What it is |
|---|---|
| `src/main/java/.../service/EmailBloomFilter.java` | Thread-safe Guava filter wrapper, fail-open, snapshot save/load |
| `src/main/java/.../service/BloomMaintenanceService.java` | Startup warm-up, atomic rebuild, catch-up from snapshot, scheduled snapshots |
| `src/main/java/.../service/UserService.java` | The lookup / create logic that uses the filter + metrics |
| `src/main/java/.../web/` | REST API (`/api/users`) and admin API (`/api/admin/bloom/*`) |
| `docs/BLOOM_FILTER_EXPLAINED.md` | Concepts, step by step |
| `docs/SIZING.md` | Memory / false-positive math for billions of rows |
| `docs/PRODUCTION_GUIDE.md` | Multi-instance, deletes, Redis, monitoring, pitfalls |
| `docs/blog/` | Beginner-friendly Medium article draft + 6 diagrams |
| `docs/schema-postgres.sql` | PostgreSQL DDL (mounted by docker-compose) |
| `docker-compose.yml` | Local PostgreSQL |
| `scripts/try-it.sh` | Curl demo that prints the savings |

## Requirements

* JDK 17+ (tested target: 17, runs on 21)
* Maven 3.9+
* Docker (only for the PostgreSQL option)

## Quick start (zero setup: in-memory H2, 200k demo users)

```bash
mvn spring-boot:run
```

Wait for the log line `Bloom filter built: 200000 rows ...`, then:

```bash
# existing user -> 200 (filter says "maybe", DB confirms)
curl -s "localhost:8080/api/users?email=user42@example.com"

# unknown user -> 404 (answered by the filter, database not touched)
curl -s -o /dev/null -w "%{http_code}\n" "localhost:8080/api/users?email=nobody@nowhere.org"

# create a user (added to the filter after commit)
curl -s -X POST localhost:8080/api/users -H 'Content-Type: application/json' \
     -d '{"email":"jane@example.com","name":"Jane"}'

# see how many DB calls were avoided
curl -s localhost:8080/api/admin/bloom/stats | jq
```

Or run the scripted demo: `bash scripts/try-it.sh`

## Run against PostgreSQL

```bash
docker compose up -d                       # starts Postgres and creates the table
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Environment variables (all optional):

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/bloomdb` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `bloom` / `bloom` | Credentials |
| `BLOOM_CAPACITY` | `1000000` | Expected number of emails (see sizing) |
| `BLOOM_FPP` | `0.01` | Target false-positive rate |
| `DEMO_SEED` / `DEMO_ROWS` | `true` / `1000000` | Seed demo rows; set `DEMO_SEED=false` for real data |

## Configuration reference (`application.yml`)

| Property | Default | Notes |
|---|---|---|
| `bloom.expected-insertions` | 1,000,000 | **Most important setting.** Rows now + growth headroom. Exceeding it makes the false-positive rate climb. |
| `bloom.fpp` | 0.01 | 0.001 costs ~50% more memory than 0.01 |
| `bloom.warmup-batch-size` | 50,000 | Keyset-paginated scan batch size |
| `bloom.warmup-async` | true | App starts immediately; until the filter is ready every lookup goes to the DB (never a wrong answer) |
| `bloom.snapshot-enabled` | false (true in `postgres` profile) | Persist the filter so a restart only scans *new* rows |
| `bloom.snapshot-path` | `./data/email-bloom.snapshot` | Put on a persistent volume |
| `bloom.snapshot-interval-ms` | 600000 | Periodic snapshot (also saved on graceful shutdown) |
| `bloom.catch-up-safety-margin` | 10000 | Re-scan this many ids below the saved high-water mark |

For a billion rows start the JVM with enough heap, e.g.:

```bash
java -Xmx6g -XX:+UseG1GC -XX:G1HeapRegionSize=32m -jar target/bloom-filter-springboot-1.0.0.jar \
     --spring.profiles.active=postgres --bloom.expected-insertions=1500000000 --bloom.fpp=0.01
```

## API

| Method & path | Description |
|---|---|
| `GET /api/users?email=` | 200 + user, or 404. Case/whitespace-insensitive. |
| `POST /api/users` | Body `{"email","name"}` → 201, or 409 if the email exists |
| `GET /api/admin/bloom/stats` | Filter state, DB calls avoided, observed false-positive rate |
| `POST /api/admin/bloom/rebuild` | Background rebuild from the DB, swapped in atomically |
| `POST /api/admin/bloom/snapshot` | Save snapshot now |
| `GET /actuator/metrics/bloom.lookup?tag=result:definitely_absent` | Micrometer counters (`definitely_absent`, `db_hit`, `false_positive`) |

> Secure `/api/admin/**` before production (Spring Security, or internal-only routing).

## Tests

```bash
mvn verify
```

* `EmailBloomFilterTest` – no false negatives, false-positive rate near target, snapshot round trip, config-change safety
* `UserApiIntegrationTest` – end-to-end with H2: found / not found / create / duplicate / rebuild-while-serving

## Design decisions (short version)

1. **Fail open.** Filter not ready → treat as "maybe" → ask the DB. The filter can only ever *save* work, never return a wrong "not found".
2. **Insert after commit.** `bloom.put()` runs only after the row is saved, so a rolled-back insert never lingers (and even if it did, it only causes a harmless false positive).
3. **Normalize emails** (trim + lower-case) identically on write, read and filter insert.
4. **The unique index stays.** The filter is an optimization, not a replacement for the database constraint.
5. **Keyset pagination** (`id > ? ORDER BY id LIMIT n`) to scan billions of rows without `OFFSET` slowdowns.
6. **Atomic rebuild.** A new filter is built on the side (live inserts go to both), then swapped.
7. **Snapshot + delta catch-up** so restarts of a multi-GB filter take seconds, not hours.

## Known limitations (read before production)

* **Deletes are not supported** by a standard Bloom filter. Deleted/changed emails stay as false positives until the next rebuild. Schedule a periodic rebuild or use a counting/Cuckoo filter – see the production guide.
* **One filter per JVM.** With N instances each holds its own copy (identical content, eventually consistent). Use RedisBloom for one shared filter – see the production guide.
* **Only helps misses.** If almost every lookup is for an existing email, the filter saves little. Check `dbCallsAvoidedPercent` in `/stats`.

## License

MIT – see `LICENSE`.
