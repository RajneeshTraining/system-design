# Bloom Filters Explained for Beginners (and How to Use One in Spring Boot to Search Billions of Emails)

*A one-stop, step-by-step guide: the idea, the math without the pain, a real use case, and a complete working Spring Boot implementation.*

**Reading time:** ~15 minutes · **Level:** Beginner → Intermediate · **Code:** [GitHub repo](GITHUB_REPO_URL)

---

## TL;DR

* A **Bloom filter** is a tiny in-memory structure that answers one question: *"Is this item in my set?"*
* Its answers are **"definitely NO"** or **"maybe YES"**. It is never wrong when it says no.
* Put it in front of a database with **billions of rows** and lookups for non-existent keys (sign-up checks, mistyped logins, bots) **never touch the database**.
* About **1.2 GB of RAM** is enough for **1 billion emails** at a 1% error rate.
* In this article we build it in **Spring Boot**, step by step, with warm-up, persistence, metrics and tests.

---

## 1. The problem: "Does this email already exist?"

Imagine you run an app with **1 billion registered users**. Your table looks like this:

```sql
CREATE TABLE users (
    id    BIGSERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    name  VARCHAR(200) NOT NULL
);
```

Every time someone types an email on the sign-up page, your API runs:

```sql
SELECT * FROM users WHERE email = 'someone@example.com';
```

You have a unique index, so it is fast, right? Yes, **for a normal database**. At billions of rows the index itself is hundreds of gigabytes. It no longer fits in memory, so many lookups need real disk or network I/O.

Now think about *who* is calling this endpoint:

* People checking whether an email is available on a sign-up form (**most answers are "not taken"**)
* Users mistyping their email on login
* Bots guessing emails (credential stuffing, enumeration)

Most of these lookups find **nothing**, yet each one still pays for a full index search. We are spending our most expensive resource, the database, to hear "no".

> **What if something cheap could answer "definitely not here" for us?**

That something is a Bloom filter.

---

## 2. What is a Bloom filter?

A Bloom filter is a **bit array** (a long row of 0s and 1s) plus a handful of **hash functions**. Burton Bloom described it in 1970, and it is still used inside databases like Cassandra, HBase and RocksDB.

It answers "is X in the set?" with one of two replies:

* **NO**: X is **definitely not** in the set. This is 100% reliable.
* **MAYBE**: X **might** be in the set. There is a small, tunable chance this is a false alarm (a *false positive*).

It never says "no" for something that is actually in the set (**no false negatives**).

Think of it as a bouncer with a smudged cheat sheet. If the sheet has no mark for your name, you are definitely not on the list. If it has marks, the bouncer is not sure, so he calls the manager (the database) to check.

---

## 3. How it works: four pictures

Let's use a tiny filter: **10 bits** and **3 hash functions**. A hash function turns an email into a position in the array.

![Bloom filter bit array walkthrough](images/01-bit-array-walkthrough.png)

1. **Start empty.** All bits are 0.
2. **Add `a@x.com`.** Its three hashes point to bits 1, 4 and 7, so we set those to 1.
3. **Add `b@x.com`.** Its hashes point to bits 2, 4 and 9. Bit 4 was already 1; that is fine, it stays 1. (Sharing bits is exactly what makes the structure tiny.)
4. **Ask questions.**
   * `c@x.com` hashes to 0, 5 and 8. Bit 0 is **0**, so one zero is enough: **definitely not present**.
   * `d@x.com` hashes to 1, 4 and 9. All three bits are 1, but they were set by *other* emails. The answer is **maybe**, and only the database can confirm.

Three facts follow from this picture:

* **A zero bit never lies.** Bits are only ever set, never cleared, so a zero proves nothing was added there.
* **All ones is only a "maybe".** The bits could have been set by different emails, which is a false positive.
* **You cannot delete.** Clearing a bit could break other emails sharing it. (More on this later.)

Also note that the filter stores **no emails**, only bits. You cannot read personal data back out of it.

---

## 4. How big does it need to be? (math without pain)

Three numbers are tied together:

* `n`: how many items you will store
* `p`: the false-positive rate you can accept (1% = 0.01)
* `m`: how many bits you need

The formulas (you will never calculate these by hand, because Guava does it for you):

```
bits needed  m = -n · ln(p) / (ln 2)²
hash count   k = (m / n) · ln 2
```

The practical rule: **about 10 bits per item buys a 1% error rate**, no matter how long the email is.

![Memory needed for 1 billion emails](images/03-memory-vs-fpp.png)

I measured this with Guava on 1 million emails:

* **1% false positives → 1,198,142 bytes** (~9.6 bits per email, 7 hashes)
* **0.1% → 1,797,206 bytes** (~14.4 bits, 10 hashes)
* **0.01% → 2,396,278 bytes** (~19.2 bits, 13 hashes)

Multiply by 1,000 and you get the chart above: **1 billion emails fit in about 1.2 GB** at 1%. A `HashSet` holding the same emails as Java strings would need many times more, and it would store the personal data too.

**Choosing p:** a false positive only costs *one normal database lookup*, which is what you pay today for every miss. So 1% is usually perfect: 99% of misses become free.

---

## 5. The use case: sign-up and login at scale

**Scenario (illustrative numbers):** your platform has **1 billion users**. The `GET /api/users?email=` endpoint gets **1,000 requests per second**, and **90% of them are for emails that do not exist** (typos, new sign-ups, bots).

![Request flow with and without a Bloom filter](images/02-request-flow.png)

* **Without a filter:** all 1,000 requests hit the database index.
* **With a 1% filter:**
  * 100 requests are for real users → the filter says "maybe" → database (same as before).
  * 900 requests are for unknown emails → the filter says "no" for ~891 of them → **no database call**. About 9 are false positives and go to the database, which returns "not found".
  * Total: **~109 database lookups per second instead of 1,000.**

![Database lookups per second before and after](images/06-use-case-db-load.png)

*(This is arithmetic on example numbers, not a benchmark. Your real savings depend on your miss rate. The project exposes the real figure at `/api/admin/bloom/stats`.)*

Notice what did **not** change: lookups for real users behave exactly as before, plus a negligible in-memory filter check.

---

## 6. Build it in Spring Boot, step by step

Everything below is in the companion repo. You need **JDK 17+** and **Maven 3.9+**. No Docker is required for the quick start (it uses in-memory H2 with 200,000 demo users); a PostgreSQL profile is included for the real thing.

### Project layout

```
src/main/java/com/example/bloom
├── BloomApplication.java
├── config/BloomProperties.java          # capacity, fpp, snapshot settings
├── domain/User.java                     # JPA entity
├── repository/UserRepository.java       # findByEmail + keyset scan
├── service/EmailBloomFilter.java        # the filter wrapper (Guava)
├── service/BloomMaintenanceService.java # warm-up, rebuild, snapshots
├── service/UserService.java             # lookup/create using the filter
├── web/UserController.java              # public API
├── web/BloomAdminController.java        # stats / rebuild / snapshot
└── demo/DemoDataSeeder.java             # optional demo data
```

### Step 1: Add the dependency

We use Guava's battle-tested `BloomFilter`. It calculates the bit count and hash count for us.

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>33.3.1-jre</version>
</dependency>
```

### Step 2: Configure capacity and error rate

```yaml
bloom:
  expected-insertions: 1000000   # rows now + growth headroom
  fpp: 0.01                      # 1% false positives at full capacity
  warmup-async: true             # start serving immediately
  snapshot-enabled: false        # true when using a real database
```

**`expected-insertions` is the most important setting.** Section 9 shows what happens if you get it wrong.

### Step 3: The entity and the repository

```java
@Entity
@Table(name = "users",
       uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 320) private String email;   // stored normalized
    @Column(nullable = false, length = 200) private String name;
    // constructors / getters omitted
}
```

```java
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Keyset pagination: WHERE id > ? ORDER BY id LIMIT n
    List<EmailView> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
}
```

> **Why keyset pagination?** To build the filter we must read every email once. `OFFSET 900000000` forces the database to skip nine hundred million rows every time. `WHERE id > :lastId ORDER BY id LIMIT 50000` uses the primary-key index, so the cost per batch stays flat even at billions of rows.

### Step 4: Wrap the Guava filter

This class has three jobs: answer questions, accept new emails, and **fail open** when it is not ready.

```java
@Component
public class EmailBloomFilter {

    private volatile BloomFilter<CharSequence> active;    // null = not ready yet
    private volatile BloomFilter<CharSequence> building;  // used during a rebuild

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** false = DEFINITELY not in the DB. true = maybe (go check the DB). */
    public boolean mightContain(String normalizedEmail) {
        BloomFilter<CharSequence> f = active;
        return f == null || f.mightContain(normalizedEmail);   // fail open
    }

    /** Call after the row is committed to the DB. */
    public void put(String normalizedEmail, long id) {
        BloomFilter<CharSequence> a = active;
        if (a != null) a.put(normalizedEmail);
        BloomFilter<CharSequence> b = building;
        if (b != null) b.put(normalizedEmail);
        // ... remember the highest id seen
    }

    BloomFilter<CharSequence> newFilter() {
        return BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                props.getExpectedInsertions(), props.getFpp());
    }
}
```

Three design decisions, each one preventing a real bug:

1. **Fail open.** If the filter is not built yet, `mightContain` returns `true`, so the request simply goes to the database. The filter can only *save* work; it can never produce a wrong "not found".
2. **Normalize.** `Jane@Example.com ` and `jane@example.com` must hash identically on every write and read path. If they do not, you create false negatives.
3. **Thread safety.** Guava's `BloomFilter` is safe for concurrent `put` and `mightContain`.

### Step 5: The lookup logic

This is the heart of the feature:

```java
public Optional<User> findByEmail(String rawEmail) {
    String email = EmailBloomFilter.normalize(rawEmail);

    if (!bloom.mightContain(email)) {
        definitelyAbsent.increment();     // no DB call at all
        return Optional.empty();
    }

    Optional<User> user = repo.findByEmail(email);
    (user.isPresent() ? dbHit : falsePositive).increment();
    return user;
}
```

And for creating users:

```java
public User create(String rawEmail, String name) {
    String email = EmailBloomFilter.normalize(rawEmail);

    if (bloom.mightContain(email) && repo.existsByEmail(email)) {
        throw new DuplicateEmailException(email);
    }
    try {
        User saved = repo.saveAndFlush(new User(email, name));
        bloom.put(email, saved.getId());     // only AFTER the row is saved
        return saved;
    } catch (DataIntegrityViolationException e) {
        throw new DuplicateEmailException(email);   // unique index is the real guard
    }
}
```

Two golden rules are visible here:

* **Insert into the filter only after the database commit succeeds.**
* **Keep the unique index.** The filter is an optimization. The database constraint is still the source of truth.

### Step 6: Warm up the filter (the part most tutorials skip)

A filter that starts empty would say "definitely not" for every existing user. So on startup we fill it from the database, in the background, while the app already serves traffic (fail-open covers the gap).

```java
private long scan(BloomFilter<CharSequence> target, long fromId) {
    long last = fromId, total = 0;
    while (true) {
        List<EmailView> batch = repo.findByIdGreaterThanOrderByIdAsc(
                last, PageRequest.of(0, props.getWarmupBatchSize()));
        if (batch.isEmpty()) break;
        for (EmailView row : batch) {
            target.put(EmailBloomFilter.normalize(row.getEmail()));
            last = row.getId();
        }
        total += batch.size();
    }
    return total;
}
```

![Filter lifecycle](images/05-lifecycle.png)

### Step 7: Make restarts fast with snapshots

Scanning a billion rows can take hours. We do not want that on every deploy. The filter can be written to disk (`filter.writeTo(out)`) and read back (`BloomFilter.readFrom(in, funnel)`). We save:

* the configured capacity and error rate (so a **config change invalidates the file automatically**),
* the highest row id seen (the *high-water mark*),
* the filter bits.

On restart we load the file and scan **only rows newer than the high-water mark** (minus a small safety margin for out-of-order commits). Snapshots are saved every 10 minutes and on graceful shutdown, written to a temp file and atomically renamed so a crash never leaves a half-written file.

### Step 8: Add the controllers

```java
@GetMapping                       // GET /api/users?email=jane@example.com
public ResponseEntity<UserResponse> findByEmail(@RequestParam @NotBlank String email) {
    return service.findByEmail(email)
            .map(u -> ResponseEntity.ok(UserResponse.of(u)))
            .orElseGet(() -> ResponseEntity.notFound().build());
}

@PostMapping                      // POST /api/users
@ResponseStatus(HttpStatus.CREATED)
public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
    return UserResponse.of(service.create(req.email(), req.name()));
}
```

Admin endpoints (secure these in production!):

* `GET /api/admin/bloom/stats`: filter state, DB calls avoided, observed false-positive rate
* `POST /api/admin/bloom/rebuild`: rebuild in the background and swap atomically
* `POST /api/admin/bloom/snapshot`: save a snapshot now

### Step 9: Run it

```bash
git clone GITHUB_REPO_URL
cd bloom-filter-springboot
mvn spring-boot:run
```

Wait for the log line `Bloom filter built: 200000 rows`, then:

```bash
# A real user: filter says "maybe", database confirms → 200
curl -s "localhost:8080/api/users?email=user42@example.com"

# An unknown email: filter says "definitely not" → 404, database untouched
curl -s -o /dev/null -w "%{http_code}\n" "localhost:8080/api/users?email=nobody@nowhere.org"

# See how much work was avoided
curl -s localhost:8080/api/admin/bloom/stats
```

The stats endpoint returns something like:

```json
{
  "filter": { "ready": true, "approximateElements": 200000, "currentExpectedFpp": 8.2E-7 },
  "lookups": { "definitelyAbsent": 1991, "dbHit": 200, "falsePositive": 9 },
  "dbCallsAvoidedPercent": 90.5,
  "observedFalsePositiveRate": 0.0045
}
```

*(Example output; your numbers will differ.)*

To use PostgreSQL instead of H2: `docker compose up -d` and run with `-Dspring-boot.run.profiles=postgres`.

---

## 7. Does it really work? What I measured

I ran the filter class on its own against Guava with 1 million emails and a 1% target:

* **False negatives: 0** out of 1,000,000 inserted emails checked.
* **False-positive rate: 1.005%** when probing a million emails that were never inserted. This matches the 1% target.
* **Snapshot save and reload:** works. Reloading with a different capacity is rejected, so the app rebuilds instead.
* **File size:** 1.2 MB for 1 million emails.

The repo also includes unit tests (no false negatives, false-positive rate near target, snapshot safety) and an integration test with H2 (found, not found, create, duplicate, rebuild while serving). Run them with `mvn verify`.

---

## 8. Alternatives: when something else is better

* **Plain index only.** Fine when the table is small enough that the index sits in RAM.
* **Caffeine / in-memory cache of recent hits.** Great for repeated lookups of the *same* keys, but caches only remember what they have seen, so unknown emails still hit the database.
* **Redis with RedisBloom (or Redisson `RBloomFilter`).** One shared filter for all application instances; costs a network hop.
* **Counting Bloom filter or Cuckoo filter.** Supports deleting items; more memory or more complexity.
* **Xor / Binary Fuse filters.** Smaller, but immutable (rebuild to change).

---

## 9. Pitfalls every beginner should know

### Pitfall 1: Under-sizing the filter

If you configure capacity for 1 million but insert 3 million, the filter fills up and false positives explode. I measured it:

![False-positive rate versus fill level](images/04-fpp-vs-load.png)

At 100% of capacity you get the promised 1%. At 150% it is **5.8%**. At 300% it is **43.6%**, so nearly half of all misses reach the database and you have lost most of the benefit. **Size for growth** (about 1.5× is a good start), watch `currentExpectedFpp`, and rebuild bigger when needed.

### Pitfall 2: Deletes and email changes

A Bloom filter cannot forget. A deleted email stays "maybe present". That is harmless (one extra database lookup, still the correct answer), but the false-positive rate drifts up. Schedule a periodic rebuild (`POST /api/admin/bloom/rebuild`), or use a counting/Cuckoo filter.

### Pitfall 3: Several application instances

Each JVM holds its own filter. An email created on instance A is not instantly known to instance B, so B could answer "definitely not" for a brand-new user. Fixes: publish inserts to all instances (Kafka / Redis pub-sub), or use a shared Redis-based filter. Duplicate sign-ups are still impossible because of the unique index.

### Pitfall 4: Normalizing differently on read and write

If you store `jane@example.com` but look up `Jane@Example.com`, your filter will correctly say "definitely not" for the string it never saw. Normalize in **one** place and use it everywhere.

### Pitfall 5: It only helps misses

If almost every lookup is for an existing email, there is nothing to save. Check `dbCallsAvoidedPercent` before and after you ship.

### Pitfall 6: Treating it as the source of truth

It is a pre-filter. Never skip the database constraint, and never use a "maybe" as a "yes".

---

## 10. Production checklist

* Capacity set to rows + growth headroom; fpp chosen (1% is a good default)
* JVM heap sized for the filter (1.2 GB at 1B rows, 1% error) plus working headroom
* Snapshots on a persistent volume; filter built from a **read replica**
* Fail-open behaviour verified (kill the app mid-warm-up; users are still found)
* Periodic rebuild scheduled if you delete or change emails
* Admin endpoints secured; rate limiting on the public lookup endpoint
* Alert if observed false-positive rate is more than 2× the configured rate
* Load-tested with a realistic mix of known and unknown emails

---

## 11. Recap

1. A Bloom filter says **"definitely no"** or **"maybe yes"**; never a wrong no.
2. About **10 bits per item** gives a 1% error rate, so **1 billion emails ≈ 1.2 GB**.
3. In front of a huge table, it removes most database work for **lookups that find nothing**.
4. In Spring Boot: Guava `BloomFilter` + **fail-open** + **normalize** + **insert after commit** + **keyset warm-up** + **snapshots** + **metrics**.
5. Know the limits: **size for growth**, **deletes need rebuilds**, **instances each hold a copy**, and **keep the unique index**.

If you found this useful, clone the repo, run it, and watch the `/stats` endpoint while you hit it with unknown emails. Seeing the "database calls avoided" number climb is the quickest way to make the idea click.

**Repo:** [GITHUB_REPO_URL](GITHUB_REPO_URL)

---

### Further reading

* Burton H. Bloom, *"Space/Time Trade-offs in Hash Coding with Allowable Errors"*, Communications of the ACM, 1970 (the original paper)
* Guava `BloomFilter` documentation
* RedisBloom documentation (for shared and Cuckoo filters)

*Tags: `bloom-filter` `spring-boot` `system-design` `java` `performance` `databases`*
