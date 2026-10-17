# Designing Twitter/X at Scale: Celebrity Fan-out with Java 21 and Spring Boot

Oct 17, 2026 · @RKG

## 1. Introduction: one tweet, fifty million timelines

When a celebrity with 50 million followers posts a tweet, the platform must make that tweet visible in tens of millions of home timelines within seconds, without slowing down anyone else. That single scenario, the **celebrity (hot-key) problem**, is what breaks naive designs, and it is the thread running through this blog.

This post designs a Twitter/X-class platform end to end, using **Java 21 and Spring Boot 3.x** as the backend, with Kafka, Cassandra, Redis, Elasticsearch and Kubernetes around it. It covers:

- Every core feature: tweets, replies, retweets, quotes, likes, bookmarks, follows, mentions, hashtags, lists, DMs, notifications, search, trends and media.
- Two ways to build a timeline (fan-out on write vs fan-out on read) and the **hybrid** model that survives celebrities.
- Real-time delivery to followers via SSE/WebSocket and mobile push.
- Capacity math, data models, code samples, caching, rate limiting, backpressure, multi-region HA, observability and security.
- The trade-offs, so you can defend each choice in an architecture review.

**The one-line answer:** push tweets into followers' timelines for normal users, **pull** celebrity tweets at read time and merge them, and keep notifications for "followed celebrity" opt-ins flowing through a separate, throttled, partitioned pipeline.

## 2. Requirements and use cases

The platform must serve a read-heavy social feed with bursty writes, so requirements are split into what users do and how well the system must do it.

### Functional requirements

| Area | Use cases |
| --- | --- |
| Identity | Sign up, login (OAuth2/OIDC), profile, 2FA, block, mute, verified badge |
| Tweets | Post (280 chars, up to 25k for premium), edit window, delete, thread, poll, schedule, media (image, GIF, video) |
| Interactions | Reply, retweet, quote tweet, like, bookmark, share link, report |
| Social graph | Follow/unfollow, followers/following lists, private accounts and follow requests, lists, suggestions |
| Timelines | Home (following), For You (ranked), user profile timeline, mentions, list timeline |
| Celebrity alerts | Opt in to "notify me when X tweets", real-time update of the open app, mobile push |
| Search | Tweet, user and hashtag search, typeahead, trending topics by region |
| Messaging | Direct messages, group chats, read receipts |
| Notifications | Likes, replies, mentions, follows, retweets, digests, in-app, push, email |
| Moderation | Spam and abuse detection, rate limits, takedown, shadow-ban, audit |
| Analytics | Impressions, engagement counts, ads and creator metrics |

### Non-functional requirements

| Goal | Target |
| --- | --- |
| Availability | 99.99% for read and post paths (about 52 minutes downtime per year) |
| Home timeline latency | p99 under 200 ms |
| Post latency | p99 under 300 ms acknowledged; fan-out is asynchronous |
| Celebrity tweet delivery | Visible to 90% of active followers under 5 s, 99% under 30 s |
| Consistency | Eventual for feeds and counters; strong for account, auth, and follow writes |
| Durability | No acknowledged tweet is ever lost (RPO near zero) |
| Scale | 500M+ monthly users, 250M daily actives, 500M tweets per day |
| Resilience | Survives a region loss and a 10x traffic spike (for example, a major live event) |

**Design stance:** this is an AP system (availability and partition tolerance) for feeds and counters, with CP islands for identity and the follow graph.

## 3. Capacity estimation

The numbers show why a celebrity tweet cannot be handled like an ordinary one: pushing it to 50 million timelines takes minutes of the cluster's entire write budget. Figures are back-of-envelope assumptions, rounded.

| Quantity | Assumption | Result |
| --- | --- | --- |
| Daily active users | 250M | - |
| New tweets per day | 500M | about 5,800/s average, about 60k/s peak |
| Home timeline loads | 250M users x 20 loads | 5B/day, about 58k/s average, 300k+/s peak |
| Read:write ratio | - | roughly 10:1 on requests, far higher on tweets served |
| Tweet metadata | about 1 KB each | about 500 GB/day, about 180 TB/year before replication |
| Media | 20% of tweets, about 500 KB average | about 50 TB/day to object storage and CDN |
| Average followers | 200 | 100B timeline inserts/day if every tweet is pushed (about 1.2M/s) |
| Celebrity tweet | 50M followers | 50M inserts; at 500k inserts/s that is 100 s of full cluster capacity |
| Timeline cache | 800 tweet IDs x 8 B per active user | about 1.6 TB raw, about 4 TB with Redis overhead and replicas |

**Takeaways**

- Timelines hold **tweet IDs only**; tweet bodies live in a separate cache, so one cached tweet serves millions of timelines.
- Pure fan-out on write fails for accounts above roughly 100k followers. Pure fan-out on read fails the 200 ms budget for everyone else. Hence the hybrid in section 6.
- Only about 30% of followers are active at any moment, so inactive users are never pushed to; their timeline is rebuilt on return.

## 4. High-level architecture and tech stack

The system is a set of Spring Boot microservices behind an API gateway, connected by Kafka events. Writes go through a thin synchronous path (validate, persist, publish) and everything expensive happens asynchronously off Kafka.

&#91;embedded content: layered architecture · 3 layers, 1 event bus\]

A post touches only the gateway, the Tweet service and its outbox before it is acknowledged; fan-out, alerts, indexing and trends all consume Kafka afterwards. Reads go from the services straight to Redis and Cassandra.

### Services

| Service | Responsibility | Store |
| --- | --- | --- |
| Edge / API Gateway | TLS, authN (JWT), rate limit, routing, WAF | Redis (limits) |
| User Service | Accounts, profiles, settings, blocks and mutes | PostgreSQL (Citus/Vitess-sharded) |
| Tweet Service | Create, read, delete, edit tweets; ID generation | Cassandra + Redis |
| Graph Service | Follow, unfollow, followers and following queries, celebrity flag | Cassandra + Redis sets |
| Fan-out Service | Pushes tweet IDs into follower timelines (non-celebrities) | Kafka consumer, Redis |
| Timeline Service | Builds home timeline: cached push part + pulled celebrity part + ranking | Redis + Cassandra |
| Engagement Service | Likes, retweets, bookmarks, counters | Cassandra + Redis counters |
| Notification Service | In-app, push (FCM/APNs), email, celebrity alerts | Kafka, Redis, Cassandra |
| Realtime Gateway | SSE/WebSocket connections for live updates | Redis Pub/Sub, in-memory |
| Search Service | Indexing and queries, typeahead | Elasticsearch/OpenSearch |
| Trends Service | Streaming hashtag counts, regional trends | Kafka Streams, Redis |
| Media Service | Upload, transcode, thumbnails | S3-compatible store + CDN |
| Ranking / Feed ML | For You candidate generation and scoring | Feature store, model server |
| Moderation / Spam | Real-time and async abuse checks | Kafka Streams, ML |
| DM Service | Conversations and messages | Cassandra, WebSocket |

### Technology choices

| Concern | Choice | Why |
| --- | --- | --- |
| Language / runtime | Java 21, Spring Boot 3.x, virtual threads | Blocking-style code with massive concurrency on I/O-bound services |
| Sync APIs | REST (Spring MVC) + gRPC between services | gRPC for low-latency internal calls |
| Event backbone | Apache Kafka (with schema registry, Avro/Protobuf) | Durable, partitioned, replayable fan-out |
| Primary tweet and timeline store | Apache Cassandra / ScyllaDB | Write-heavy, linear scale, multi-region |
| Relational data | PostgreSQL (sharded) | Accounts and settings need transactions |
| Cache | Redis Cluster (timelines, tweet cache, counters) | Sub-millisecond reads, sorted sets and lists |
| Search | Elasticsearch / OpenSearch | Inverted index, near-real-time |
| Media | S3/GCS + CDN (CloudFront/Akamai) | Offload bytes from the app tier |
| Stream processing | Kafka Streams / Flink | Trends, counters, spam signals |
| Resilience | Resilience4j, Bucket4j | Circuit breakers, bulkheads, rate limits |
| Observability | OpenTelemetry, Micrometer, Prometheus, Grafana, Loki, Tempo | Metrics, logs, traces |
| Platform | Kubernetes, Istio, Terraform, Argo CD | Autoscaling, mTLS, GitOps |

**Why virtual threads:** the services spend most of their time waiting on Redis, Cassandra and Kafka. Java 21 virtual threads (`spring.threads.virtual.enabled=true`) let each request be written as simple blocking code while the JVM multiplexes millions of them over a small carrier pool, which removes most of the reactive-programming complexity.

## 5. Data model and storage

### Tweet IDs

Use a **Snowflake-style 64-bit ID**: 41 bits of timestamp, 10 bits of datacenter/worker, 12 bits of sequence. IDs are roughly time-sortable, so "newest first" is a sort on the ID itself, and no central database is needed to mint them (about 4,096 IDs per millisecond per worker).

### Core tables (Cassandra, query-first design)

```sql
-- Source of truth for tweets, partitioned by tweet id
CREATE TABLE tweets (
  tweet_id     bigint PRIMARY KEY,
  author_id    bigint,
  body         text,
  reply_to_id  bigint,
  quote_of_id  bigint,
  retweet_of_id bigint,
  media_ids    list<bigint>,
  created_at   timestamp,
  deleted      boolean
);

-- A user's own profile timeline, bucketed by month to bound partition size
CREATE TABLE user_tweets (
  author_id bigint, bucket text, tweet_id bigint,
  PRIMARY KEY ((author_id, bucket), tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);

-- Durable copy of precomputed home timelines (Redis is the hot copy)
CREATE TABLE home_timeline (
  user_id bigint, tweet_id bigint,
  PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC)
  AND default_time_to_live = 1209600;

-- Social graph, stored in both directions
CREATE TABLE followers (user_id bigint, follower_id bigint, since timestamp,
  PRIMARY KEY (user_id, follower_id));
CREATE TABLE following (user_id bigint, followee_id bigint, since timestamp,
  PRIMARY KEY (user_id, followee_id));

-- Likes: one row per (tweet, user) for idempotency; counts live elsewhere
CREATE TABLE tweet_likes (tweet_id bigint, user_id bigint, at timestamp,
  PRIMARY KEY (tweet_id, user_id));
```

### Placement of data

| Data | Hot store | Durable store | Notes |
| --- | --- | --- | --- |
| Tweet body and metadata | Redis (LRU, hours) | Cassandra | Cache-aside, hot tweets pinned |
| Home timeline | Redis list/sorted set, 800 IDs | Cassandra (14-day TTL) | Only active users are kept hot |
| Follower sets | Redis for small accounts | Cassandra | Celebrity follower lists are paged, never loaded whole |
| Counters (likes, retweets, views) | Redis `INCR`, sharded | Cassandra counter table, flushed in batches | Eventually consistent |
| Accounts, auth, settings | - | PostgreSQL | Strong consistency |
| Media | CDN | Object storage | Metadata row points to the object key |
| Search | - | Elasticsearch | Rebuilt from Kafka, never a source of truth |

**Sharding rule:** partition by the entity that is read together (user for timelines, tweet for engagement). A hot celebrity tweet gets **key salting** (`tweet_id#0..N`) for its counters, so no single Redis shard takes millions of increments per second.

## 6. Posting a tweet and the hybrid fan-out

### The write path (synchronous part)

1. Client calls `POST /v1/tweets` with an `Idempotency-Key` header.
2. Gateway authenticates the JWT and applies the per-user rate limit.
3. Tweet Service validates length and media, runs a fast spam check, and mints a Snowflake ID.
4. It writes the tweet to Cassandra (`LOCAL_QUORUM`) and the author's profile timeline, then caches the body in Redis.
5. It publishes a `TweetCreated` event to Kafka, keyed by `author_id`, and returns `201` with the tweet ID.

Steps 4 and 5 use the **transactional outbox** pattern, so the tweet is never saved without its event, and never published without being saved. Everything after step 5 is asynchronous.

### Three timeline strategies compared

| Strategy | How it works | Read cost | Write cost | Fails when |
| --- | --- | --- | --- | --- |
| Fan-out on read (pull) | Merge tweets of all followees at read time | Very high | Very low | Users follow thousands of accounts; p99 latency explodes |
| Fan-out on write (push) | Copy tweet ID into every follower's timeline at post time | Very low | Very high | Author has millions of followers (hot key, write storm) |
| **Hybrid** | Push for normal authors, pull and merge for celebrities | Low | Moderate | Needs a correct celebrity classification |

### The hybrid rule

The Graph Service flags an account as a **celebrity** when its follower count crosses a threshold (for example 100,000), with hysteresis (promote at 100k, demote at 80k) so accounts do not flap.

- **Normal author:** the Fan-out Service reads the follower list in pages of 1,000 and, for each **active** follower (seen in the last 30 days), runs `LPUSH` plus `LTRIM 800` on that follower's Redis timeline. Work is split across Kafka partitions and consumer instances, so a 90k-follower account finishes in seconds.
- **Celebrity:** the tweet is **not** pushed. It lives only in the celebrity's own tweet list. When a follower opens the home timeline, the Timeline Service takes the cached pushed IDs, adds the latest tweets from the (usually fewer than 20) celebrities that user follows, merges by ID and returns the page.

### Why this survives a 50M-follower tweet

- The post causes **one** write to the celebrity's list, not 50 million.
- Read cost is bounded: one extra batched lookup per followed celebrity, served from a hot Redis cache that is shared by every follower.
- Celebrity tweets are cached and replicated across Redis replicas (a read-through cache with request coalescing), so the hot key spreads over many nodes.

### Celebrity alerts: real-time to the people who asked

Most followers are fine with the pull model, but users who turn on the bell icon expect an instant alert. This is a separate **notification fan-out**:

1. `TweetCreated` for a celebrity triggers the Notification Service.
2. It reads the **subscriber list** (only opted-in followers, typically 1-5% of the audience) in pages, never the entire follower list.
3. Pages are published to a `celebrity-alerts` Kafka topic with many partitions, so hundreds of workers drain it in parallel.
4. Each worker checks mute, block and quiet-hours preferences, de-duplicates by `(user, tweet)`, then sends through FCM/APNs in batches.
5. Users currently online get the update through the Realtime Gateway instead of a push.

This is rate-limited per provider (FCM/APNs have their own quotas) and prioritised: opted-in alerts outrank generic activity notifications.

### Other write-path use cases

- **Reply / thread:** stores `reply_to_id`, increments the parent's reply counter, and notifies the parent author. Conversation view is a tree query by `conversation_id`.
- **Retweet / quote:** a new tweet row pointing to the original; the original's counters update asynchronously.
- **Delete:** soft-delete flag first (instant), then an async cleaner removes IDs from caches and search; timelines tolerate dangling IDs by filtering at read time.
- **Edit:** a versioned row inside an edit window; the timeline always renders the latest version.
- **Scheduled tweet:** stored with a `publish_at` and picked up by a sharded scheduler (Quartz or a Kafka delay topic).
- **Mentions and hashtags:** parsed on write and emitted as events to Notifications, Search and Trends.

## 7. Timeline read path and real-time delivery

### Home timeline (GET /v1/timeline/home)

1. Read the first page of tweet IDs from the user's Redis timeline (pushed part).
2. In parallel (virtual threads, `StructuredTaskScope`), fetch the latest IDs of each followed celebrity from their hot tweet lists.
3. Merge the two ID lists by Snowflake order and cut the page, using a **cursor** (`max_id`), never an offset.
4. **Hydrate**: batch-get tweet bodies (`MGET`), author profiles, counters and viewer-specific flags (liked, retweeted, bookmarked) in a handful of round trips.
5. Filter out deleted, blocked, muted and age-restricted items; optionally pass through the ranker.
6. Return the page with `ETag` and a `next_cursor`.

**Cold start:** if the user's Redis timeline has expired (inactive user), rebuild it from Cassandra `home_timeline`, or, if empty, run a fan-out-on-read over followees (capped), then re-warm the cache. This is what makes it safe to skip pushing to inactive users.

### For You (ranked) feed

A three-stage funnel, run within a latency budget of about 150 ms:

1. **Candidate generation:** in-network (followed accounts), out-of-network (graph neighbours, topic interests, trending), about 1,500 candidates.
2. **Light ranking:** a small model scores candidates using precomputed features from a feature store (author affinity, recency, past engagement).
3. **Heavy ranking and mixing:** a larger model re-ranks the top few hundred, then business rules enforce diversity, safety filters and ad slots.

If the ranking service is slow or down, the Timeline Service **degrades to the reverse-chronological home feed**. The user still gets a feed.

### Real-time delivery to open apps

- Clients keep an **SSE** connection (or WebSocket for DMs) to the Realtime Gateway, which holds connections in memory and is stateless apart from a connection registry in Redis.
- When a followed account posts, the pipeline publishes a small event (`new_tweets_available`, not the tweet) to the user's channel. The client shows "N new tweets" and fetches on tap. This keeps the real-time path cheap and avoids pushing 50 million payloads.
- At 10M+ concurrent connections, gateways scale horizontally behind an L4 load balancer; the user-to-gateway mapping lives in Redis, and a Kafka topic partitioned by gateway ID routes events to the correct node.
- For celebrity tweets, the gateway subscribes only the **currently connected** followers, so cost depends on who is online, not on follower count.

### Mobile push notifications

The pipeline from section 6 ends at FCM (Android) and APNs (iOS). Workers batch up to 500 tokens per FCM request, retry with exponential backoff and jitter, honour provider rate limits, prune invalid device tokens, and collapse repeated alerts (`collapse_key`) so users are not spammed during a burst of tweets.

## 8. The rest of the feature set

### Social graph (follow, unfollow, private accounts)

- Follow writes are **strongly consistent** for the pair `(follower, followee)` and update both the `followers` and `following` tables through one batch/outbox event; the follower counter is updated asynchronously.
- Private accounts create a `follow_request` row; approval flips it into a follow and triggers a backfill of the last N tweets into the new follower's timeline.
- Unfollow, block and mute are applied at **read time** (filter) and lazily cleaned from timeline caches, which avoids expensive rewrites.
- Follower lists use cursor paging; a celebrity's list is never materialised in memory.
- "Who to follow" is an offline job (graph neighbours, co-follows) writing suggestions into Redis.

### Engagement: likes, retweets, bookmarks, views

- A like is an idempotent upsert on `(tweet_id, user_id)`; the **counter** is separate and eventually consistent.
- Counters absorb bursts in Redis (`INCRBY`, with salted shards for hot tweets), and a flusher writes totals to Cassandra every few seconds. Reads sum the shards.
- Per-user flags (liked, retweeted) come from a Bloom filter plus the `tweet_likes` table, checked in batch during hydration.
- Impressions and views are fire-and-forget events to Kafka, aggregated by a stream job, never written inline.

### Search and typeahead

- Tweets are indexed from the `TweetCreated` Kafka topic into **time-partitioned Elasticsearch indices** (hot, warm, cold tiers); recent data is searched first.
- Ranking blends text relevance, recency and engagement. Deleted tweets are removed via tombstone events.
- Typeahead uses edge n-gram indices and a Redis-cached prefix store for popular queries; results arrive in under 50 ms.
- Hashtag and user search have their own small indices to keep the main one lean.

### Trending topics

- A Kafka Streams / Flink job counts hashtags and phrases in sliding windows (for example 5 minutes vs the previous hour), per region.
- A topic trends when its velocity is a statistical outlier versus its baseline, after spam and bot filtering and a safety review list.
- Results are written to Redis every minute and served from cache.

### Media pipeline

- The client asks the Media Service for a **pre-signed upload URL** and uploads directly to object storage, so the app tier never carries the bytes (resumable and chunked for video).
- An object-created event triggers workers: virus scan, image resize variants, video transcoding to adaptive bitrate (HLS), thumbnails, content moderation.
- Everything is served through a CDN with long cache lifetimes and content-hash URLs.

### Notifications (all types)

Events from likes, replies, mentions and follows land on a `notifications` topic. A worker aggregates ("Ana and 40 others liked your tweet"), applies user preferences and mutes, stores a notification row, and chooses the channel: in-app badge, push, email digest. Aggregation windows prevent a viral tweet from sending a million separate pushes to its author.

### Direct messages

Conversation-partitioned Cassandra tables, WebSocket delivery with ack and retry, read receipts, end-to-end encryption as an optional layer, and offline queueing with push.

### Moderation, spam and abuse

A synchronous lightweight check on post (rate, link reputation, duplicate content) plus an asynchronous ML pipeline on Kafka Streams for deeper signals. Actions range from rate limiting to shadow-banning and takedown, all audit-logged. Reports go to a review queue with priority by reach.

## 9. Spring Boot 3 and Java 21 implementation

The samples below are condensed to show the critical paths: configuration, ID generation, the idempotent post, the fan-out consumer, and the hybrid timeline read. Cross-cutting code (security, DTO mapping, tests) is omitted.

### Configuration

```yaml
spring:
  threads:
    virtual:
      enabled: true          # Tomcat + @Async + Kafka listeners run on virtual threads
  kafka:
    producer:
      acks: all
      properties:
        enable.idempotence: true
        compression.type: lz4
        linger.ms: 5
    consumer:
      enable-auto-commit: false
      properties:
        max.poll.records: 200
  cassandra:
    local-datacenter: dc-east
    request:
      consistency: LOCAL_QUORUM
resilience4j:
  circuitbreaker:
    instances:
      ranker:
        slidingWindowSize: 50
        failureRateThreshold: 40
        waitDurationInOpenState: 10s
  bulkhead:
    instances:
      ranker:
        maxConcurrentCalls: 200
```

### Snowflake ID generator

```java
final class SnowflakeIdGenerator {
    private static final long EPOCH = 1735689600000L;      // 2025-01-01
    private final long workerId;                           // 0..1023, assigned per pod
    private final ReentrantLock lock = new ReentrantLock(); // virtual-thread friendly
    private long lastTs = -1L, seq = 0L;

    SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > 1023) throw new IllegalArgumentException("workerId");
        this.workerId = workerId;
    }

    long next() {
        lock.lock();
        try {
            long ts = Math.max(System.currentTimeMillis(), lastTs); // tolerate small clock skew
            if (ts == lastTs) {
                seq = (seq + 1) & 4095;
                if (seq == 0) {                                   // sequence exhausted this ms
                    while ((ts = System.currentTimeMillis()) <= lastTs) Thread.onSpinWait();
                }
            } else {
                seq = 0;
            }
            lastTs = ts;
            return ((ts - EPOCH) << 22) | (workerId << 12) | seq;
        } finally {
            lock.unlock();
        }
    }
}
```

### Post-tweet API with idempotency and outbox

```java
record CreateTweetRequest(@NotBlank @Size(max = 280) String body,
                          Long replyToId, List<Long> mediaIds) {}
record TweetResponse(long id) {}
record TweetCreated(long tweetId, long authorId, Long replyToId, Instant createdAt) {}

@RestController
@RequestMapping("/v1/tweets")
class TweetController {
    private final TweetService service;
    TweetController(TweetService service) { this.service = service; }

    @PostMapping
    ResponseEntity<TweetResponse> create(@AuthenticationPrincipal Jwt jwt,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreateTweetRequest req) {
        long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, key, req));
    }
}

@Service
class TweetService {
    private final SnowflakeIdGenerator ids;
    private final CassandraTemplate cassandra;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    TweetService(SnowflakeIdGenerator ids, CassandraTemplate cassandra,
                 StringRedisTemplate redis, ObjectMapper json) {
        this.ids = ids; this.cassandra = cassandra; this.redis = redis; this.json = json;
    }

    TweetResponse create(long userId, String idemKey, CreateTweetRequest req) {
        long tweetId = ids.next();
        String lockKey = "idem:%d:%s".formatted(userId, idemKey);
        Boolean first = redis.opsForValue()
                .setIfAbsent(lockKey, Long.toString(tweetId), Duration.ofHours(24));
        if (Boolean.FALSE.equals(first)) {                    // client retry: return the original result
            return new TweetResponse(Long.parseLong(redis.opsForValue().get(lockKey)));
        }
        Instant now = Instant.now();
        var event = new TweetCreated(tweetId, userId, req.replyToId(), now);

        // One logged batch: tweet + profile timeline + outbox row become durable together.
        // A relay (or CDC) publishes outbox rows to Kafka at-least-once; consumers are idempotent.
        cassandra.batchOps()
                .insert(new TweetEntity(tweetId, userId, req.body(), req.replyToId(), now))
                .insert(new UserTweetEntity(userId, YearMonth.from(now.atZone(ZoneOffset.UTC)).toString(), tweetId))
                .insert(new OutboxEntity(tweetId, "tweet-created", Long.toString(userId), toJson(event)))
                .execute();

        redis.opsForValue().set("tw:" + tweetId, req.body(), Duration.ofHours(6)); // warm tweet cache
        return new TweetResponse(tweetId);
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
```

### Fan-out consumer (normal authors only)

```java
@Component
class FanoutConsumer {
    private static final int TIMELINE_MAX = 800;
    private final GraphClient graph;
    private final RedisAdvancedClusterAsyncCommands<String, String> redis; // Lettuce cluster client

    FanoutConsumer(GraphClient graph, RedisAdvancedClusterAsyncCommands<String, String> redis) {
        this.graph = graph; this.redis = redis;
    }

    @KafkaListener(topics = "tweet-created", groupId = "fanout", concurrency = "12")
    void onTweet(TweetCreated e, Acknowledgment ack) {
        if (graph.isCelebrity(e.authorId())) {      // celebrities are pulled at read time
            ack.acknowledge();
            return;
        }
        String id = Long.toString(e.tweetId());
        String cursor = null;
        do {
            FollowerPage page = graph.activeFollowers(e.authorId(), cursor, 1000);
            List<RedisFuture<?>> pending = new ArrayList<>(page.ids().size() * 2);
            for (long follower : page.ids()) {
                String key = "tl:" + follower;
                pending.add(redis.lpush(key, id));
                pending.add(redis.ltrim(key, 0, TIMELINE_MAX - 1));
            }
            LettuceFutures.awaitAll(Duration.ofSeconds(3), pending.toArray(new RedisFuture[0]));
            cursor = page.next();
        } while (cursor != null);
        ack.acknowledge();                           // commit offset only after the full fan-out
    }
}

record FollowerPage(List<Long> ids, String next) {}
```

Failures retry through Spring Kafka's `DefaultErrorHandler` with exponential backoff and then a dead-letter topic. A re-delivered tweet is harmless: the timeline writer skips an ID that is already at the head, and the reader de-duplicates anyway.

### Hybrid home timeline

```java
@Service
class TimelineService {
    private final TimelineCache timelineCache;   // Redis lists of tweet IDs
    private final TweetCache tweetCache;         // Redis + Cassandra fallback, MGET batched
    private final GraphClient graph;
    private final RankerClient ranker;

    TimelineService(TimelineCache t, TweetCache c, GraphClient g, RankerClient r) {
        this.timelineCache = t; this.tweetCache = c; this.graph = g; this.ranker = r;
    }

    TimelinePage home(long userId, Long maxId, int limit) {
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<Long>> pushed = scope.submit(() -> timelineCache.ids(userId, maxId, limit * 2));
            Future<List<Long>> pulled = scope.submit(() -> celebrityIds(userId, maxId, limit));

            List<Long> merged = Stream.concat(pushed.get().stream(), pulled.get().stream())
                    .distinct()
                    .sorted(Comparator.reverseOrder())      // Snowflake IDs sort by time
                    .limit(limit)
                    .toList();

            List<Tweet> tweets = tweetCache.multiGet(merged).stream()
                    .filter(t -> !t.deleted() && !graph.isBlockedOrMuted(userId, t.authorId()))
                    .toList();
            Long next = merged.isEmpty() ? null : merged.getLast();   // Java 21 SequencedCollection
            return new TimelinePage(tweets, next);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private List<Long> celebrityIds(long userId, Long maxId, int limit) throws Exception {
        List<Long> celebs = graph.followedCelebrities(userId);   // usually under 20, cached per user
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Long>>> parts = celebs.stream()
                    .map(c -> ex.submit(() -> tweetCache.recentIdsByAuthor(c, maxId, limit)))
                    .toList();
            List<Long> out = new ArrayList<>();
            for (var f : parts) out.addAll(f.get());
            return out;
        }
    }

    @CircuitBreaker(name = "ranker", fallbackMethod = "chronological")
    @Bulkhead(name = "ranker")
    TimelinePage forYou(long userId, int limit) {
        return ranker.rank(userId, limit);
    }

    TimelinePage chronological(long userId, int limit, Throwable cause) {   // graceful degradation
        return home(userId, null, limit);
    }
}
```

Three details matter in production: celebrity tweet lists are served from a **per-JVM Caffeine cache (1-2 s TTL) in front of Redis**, so a hot account costs one Redis hit per pod per second instead of one per request; `followedCelebrities` is cached per user and invalidated on follow or unfollow; and cursors are tweet IDs, so pages stay stable while new tweets arrive.

## 10. Caching, rate limiting and surviving peak traffic

Peaks come from live events, breaking news and celebrity posts, and they arrive as a sudden multiple of normal load. The strategy is layered: absorb at the edge, shed what is optional, protect what is essential, and let queues smooth the rest.

### Multi-layer caching

| Layer | What | TTL | Purpose |
| --- | --- | --- | --- |
| CDN | Media, profile images, public tweet pages, static assets | minutes to a year (content-hash URLs) | Takes the bulk of bytes off the origin |
| Edge / gateway | Anonymous reads (public profile, tweet permalink) | 1-5 s | Collapses viral read bursts |
| In-process (Caffeine) | Celebrity tweet lists, follow-celebrity sets, feature flags | 1-2 s | Removes hot-key load from Redis |
| Redis cluster | Timelines, tweet bodies, counters, sessions | hours | Sub-millisecond reads |
| Database | Cassandra, PostgreSQL, Elasticsearch | - | Source of truth |

Anti-stampede techniques: **request coalescing** (one loader per key, others wait), **TTL jitter** (no mass expiry at the same second), **stale-while-revalidate**, and **negative caching** for missing keys.

### Hot-key protection

- Salt hot counters over N shards and sum on read.
- Replicate hot tweet bodies to every Redis replica and read from replicas.
- Use a local "hot key detector" (a Count-Min Sketch) that promotes keys to the in-process cache automatically.

### Rate limiting (token bucket in Redis)

Limits apply per user, per IP and per API key, with stricter buckets on writes (for example 300 tweets per 3 hours, 1,000 likes per day) to stop spam and bound fan-out.

```java
@Component
class RedisRateLimiter {
    // KEYS[1]=bucket, ARGV: capacity, refill/sec, now(ms), cost. Returns 1 if allowed, 0 if not.
    private static final RedisScript<Long> SCRIPT = RedisScript.of("""
        local d = redis.call('HMGET', KEYS[1], 't', 'ts')
        local cap, rate, now, cost = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4])
        local tokens = tonumber(d[1]) or cap
        local ts = tonumber(d[2]) or now
        tokens = math.min(cap, tokens + (now - ts) / 1000.0 * rate)
        local ok = 0
        if tokens >= cost then tokens = tokens - cost; ok = 1 end
        redis.call('HSET', KEYS[1], 't', tokens, 'ts', now)
        redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate * 1000) * 2)
        return ok
        """, Long.class);

    private final StringRedisTemplate redis;
    RedisRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    boolean allow(String bucket, int capacity, double refillPerSec, int cost) {
        Long r = redis.execute(SCRIPT, List.of("rl:" + bucket),
                Integer.toString(capacity), Double.toString(refillPerSec),
                Long.toString(System.currentTimeMillis()), Integer.toString(cost));
        return Long.valueOf(1).equals(r);
    }
}
```

The gateway returns `429` with `Retry-After`. If Redis itself is unreachable, the limiter **fails open for reads and fails closed for writes**.

### Backpressure and load shedding

| Technique | Where | Effect |
| --- | --- | --- |
| Kafka as shock absorber | Between post and fan-out | A traffic spike becomes queue lag, not an outage; consumers scale on lag (KEDA) |
| Priority topics | Fan-out, notifications | Celebrity alerts and DMs on a high-priority topic, bulk digests on a low one |
| Bulkheads | Per downstream call (Resilience4j) | A slow ranker cannot exhaust the timeline thread pool |
| Circuit breakers + fallbacks | Ranker, search, media, notifications | Serve chronological feed, cached results or a degraded view |
| Timeouts and retries with jitter | Every remote call | Budgeted retries only; no retry storms |
| Adaptive load shedding | Gateway | Under overload, drop lowest-value traffic first: analytics, suggestions, trends refresh, then ranking, never posting or timeline read |
| Fan-out throttle | Fan-out Service | Caps timeline writes per second per author so one tweet cannot starve the cluster |
| Autoscaling | HPA on CPU and RPS, KEDA on Kafka lag, pre-scaling for scheduled events | Capacity follows demand; warm pools avoid JVM cold starts |

### Making the JVM fast

- Virtual threads for I/O-bound request handling; keep CPU-heavy work (ranking math, JSON of large pages) on a bounded platform pool.
- Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) for low pause times at large heaps.
- Avoid `synchronized` around blocking I/O on virtual threads (use `ReentrantLock`) to prevent carrier-thread pinning.
- Connection pools sized by Little's law; GraalVM native image or CRaC/AppCDS for faster pod start where it fits.
- Protobuf or Avro on internal hops, gzip/brotli at the edge, HTTP/2 or HTTP/3 to clients.

### Load testing and game days

Replay production traffic with a 10x multiplier (Gatling or k6), including a synthetic celebrity tweet to a 50M-follower test account, and verify the SLOs hold and graceful degradation triggers in the planned order.

## 11. High availability, security, observability and deployment

### Multi-region high availability

- **Active-active across at least three regions.** GeoDNS or anycast routes users to the nearest healthy region; each region can serve reads and writes on its own.
- **Cassandra** replicates across regions (`NetworkTopologyStrategy`, RF=3 per region); clients use `LOCAL_QUORUM`, so a remote region outage never blocks local traffic.
- **Kafka** runs per region with MirrorMaker 2 (or cluster linking) replicating critical topics; consumers are idempotent so replays are safe.
- **PostgreSQL** accounts use a primary per shard with synchronous replicas in-region and asynchronous cross-region replicas; automated failover with Patroni.
- **Redis** is a cache, rebuildable from Cassandra, so a lost cluster costs latency, not data. Replicas span availability zones.
- **Each region spans three availability zones**; pods use topology spread constraints and PodDisruptionBudgets.
- **Conflict handling:** tweets and likes are append-style with unique IDs, so there are no write conflicts; profile edits use last-write-wins with a version vector.

| Failure | System behaviour |
| --- | --- |
| Pod or node loss | Kubernetes reschedules; load balancer drains; no user impact |
| Availability zone loss | Quorums remain; autoscaler adds capacity in surviving zones |
| Redis cluster loss | Timelines rebuilt lazily from Cassandra; higher latency for a short time |
| Kafka lag or outage | Posts still acknowledged via outbox; fan-out catches up when consumers recover |
| Ranker or search down | Circuit breaker opens; chronological feed and cached search |
| Region loss | Traffic shifts to other regions in under a minute; RPO close to zero for tweets |
| Bad deploy | Canary analysis aborts at 1-5% of traffic; automated rollback |

### Security and privacy

- OAuth 2.1 / OIDC with short-lived JWT access tokens and rotating refresh tokens; Spring Security resource servers validate at the gateway and again in services (zero trust).
- mTLS between services via Istio; secrets in Vault or a cloud KMS; field-level encryption for sensitive data.
- WAF, bot detection, CAPTCHA step-up, device fingerprinting and credential-stuffing defences at the edge.
- Input validation and output encoding against injection and XSS; safe URL unfurling with SSRF protection.
- Privacy: GDPR-style export and deletion workflows (a delete event propagates to every store), data residency, audit trails, and retention limits.

### Observability

- **Metrics (Micrometer to Prometheus):** request rate, errors, latency percentiles, Kafka consumer lag, fan-out duration per tweet, cache hit ratio, Redis memory, GC pauses, virtual-thread pinning events.
- **Tracing (OpenTelemetry to Tempo/Jaeger):** one trace from `POST /tweets` through Kafka to the push notification, so end-to-end delivery time is measurable.
- **Logging:** structured JSON with trace IDs into Loki or ELK; sampling at high volume.
- **SLOs and alerts:** burn-rate alerts on availability and p99 latency; a business SLO for "celebrity tweet delivered to 90% of active followers in under 5 s".
- **Synthetic probes** post and read from every region continuously.

### Deployment

- Containers on Kubernetes with Helm charts, **Argo CD** GitOps, and Terraform for infrastructure.
- Progressive delivery (Argo Rollouts): canary, automated metric analysis, instant rollback; feature flags for dark launches.
- Separate node pools for latency-critical services, batch/stream jobs and the Realtime Gateway (connection-heavy).
- Zero-downtime Cassandra and Kafka upgrades by rolling restarts; schema evolution via backward-compatible Avro/Protobuf.
- CI: build, unit and contract tests (Pact), Testcontainers integration tests, vulnerability scans, SBOM.

### Cost levers

Push to active users only, keep tweet IDs rather than bodies in timelines, tier Elasticsearch and object storage (hot/warm/cold), compress Kafka, use spot instances for stateless stream and batch workers, and cache aggressively at the CDN.

## 12. Trade-offs, edge cases and conclusion

### Decisions worth defending in a review

| Decision | Chosen | Alternative | Why, and what it costs |
| --- | --- | --- | --- |
| Timeline model | Hybrid push/pull | Pure push or pure pull | Bounded write amplification and bounded read latency; costs a celebrity flag and a merge step |
| Consistency | Eventual for feeds and counters | Strong everywhere | Availability and speed; users may see a like count a few seconds stale |
| Tweet store | Cassandra/Scylla | Sharded MySQL | Write throughput and multi-region; weaker ad-hoc queries, so query-first modelling |
| Concurrency | Virtual threads | Reactive WebFlux | Simple, debuggable code; need to avoid pinning and watch native-call blocking |
| Delivery to live clients | Signal "new tweets", then fetch | Push full payloads | Cheap at 50M followers; one extra round trip on tap |
| Counters | Redis shards + batched flush | Per-event DB write | Absorbs hot keys; a crash can lose a few seconds of increments (reconcilable from the event log) |
| IDs | Snowflake | UUID or DB sequence | Time-sortable and generated locally; needs unique worker IDs and clock-skew handling |

### Edge cases to design for

- **Account crosses the celebrity threshold:** the flag flips with hysteresis; existing pushed entries stay valid, new tweets are pulled.
- **User follows a celebrity:** no backfill is needed, because pull sees their tweets immediately. For normal accounts, backfill the last 20 tweets asynchronously.
- **Deleted tweet already in many timelines:** filtered at hydration; cleaners remove IDs lazily.
- **Out-of-order or duplicate events:** Snowflake ordering plus idempotent consumers make replays safe.
- **Thundering herd after an outage:** jittered client retries, token-bucket reconnects on the Realtime Gateway, and gradual cache warming.
- **Viral reply storms:** reply counters are sampled, and conversation views page by cursor with a cap on visible depth.
- **Abusive fan-out:** a spam account with many followers is rate-limited and shadow-banned before fan-out starts.
- **Clock skew and zombie pods:** fenced worker IDs from a coordination service (etcd or ZooKeeper leases).

### Conclusion

The whole design rests on one idea: **spend work where it is cheap and bounded.** Normal authors are pushed to active followers on write, celebrities are pulled at read from a hot shared cache, and alerts reach opted-in followers through a throttled, partitioned pipeline. Kafka decouples posting from delivery so spikes become lag instead of failures. Java 21 virtual threads keep the service code simple while handling very high concurrency, and layered caching, rate limiting, bulkheads and graceful degradation keep the platform usable under 10x load.

From here, the natural next steps are a prototype of the Fan-out and Timeline services with Testcontainers, a load test that includes a synthetic 50M-follower account, and then the ranking and search pipelines.

# Appendix: Complete data and object model

This appendix turns the design into concrete models: the Java domain objects, the entity relationships, and the physical schema in each store. Cassandra tables are designed query-first, PostgreSQL holds data that needs transactions, and Redis, Elasticsearch and Kafka hold derived or in-flight data.

## A1. Domain object model (Java 21)

Immutable **records** model values and events, **sealed interfaces** model variants (tweet kinds, notification kinds, media), and enums hold closed sets. All IDs are Snowflake `long` values wrapped in typed IDs where it helps prevent mix-ups.

```java
// ---------- Identity ----------
record UserId(long value) {}
record TweetId(long value) {}

enum AccountStatus { ACTIVE, SUSPENDED, DEACTIVATED, LOCKED }
enum Visibility    { PUBLIC, PROTECTED }            // protected = private account
enum VerifiedType  { NONE, BLUE, ORG, GOVERNMENT }

record User(UserId id, String handle, String displayName, String email, String phoneHash,
            String bio, String location, URI avatarUrl, URI bannerUrl,
            Visibility visibility, VerifiedType verified, AccountStatus status,
            boolean celebrity,                       // derived flag from Graph Service
            Instant createdAt, Instant updatedAt) {}

record UserCounters(UserId id, long followers, long following, long tweets, long listed) {}

// ---------- Tweets (sealed variants) ----------
sealed interface Tweet permits OriginalTweet, Reply, Retweet, QuoteTweet {
    TweetId id();
    UserId authorId();
    Instant createdAt();
}
record OriginalTweet(TweetId id, UserId authorId, String body, List<MediaRef> media,
                     Poll poll, Entities entities, Language lang, Source source,
                     Instant createdAt, Instant scheduledFor, boolean deleted) implements Tweet {}
record Reply(TweetId id, UserId authorId, String body, List<MediaRef> media, Entities entities,
             TweetId inReplyTo, TweetId conversationId, UserId inReplyToUser,
             Instant createdAt) implements Tweet {}
record Retweet(TweetId id, UserId authorId, TweetId original, Instant createdAt) implements Tweet {}
record QuoteTweet(TweetId id, UserId authorId, String body, TweetId quoted,
                  Entities entities, Instant createdAt) implements Tweet {}

record Entities(List<Hashtag> hashtags, List<Mention> mentions, List<UrlEntity> urls) {}
record Hashtag(String tag, int start, int end) {}
record Mention(UserId userId, String handle, int start, int end) {}
record UrlEntity(URI expanded, String shortCode, String title, int start, int end) {}

record TweetEdit(TweetId tweetId, int version, String body, Instant editedAt) {}   // edit history
record TweetCounters(TweetId id, long likes, long retweets, long quotes,
                     long replies, long bookmarks, long views) {}

// ---------- Media and polls ----------
enum MediaType { IMAGE, GIF, VIDEO }
enum MediaState { UPLOADING, PROCESSING, READY, REJECTED }
record MediaRef(long mediaId, MediaType type, String objectKey, int width, int height,
                Integer durationMs, String altText, MediaState state) {}
record Poll(long pollId, List<PollOption> options, Instant endsAt) {}
record PollOption(int position, String label, long votes) {}

// ---------- Social graph ----------
record Follow(UserId follower, UserId followee, Instant since) {}
record FollowRequest(UserId requester, UserId target, Instant requestedAt) {}
record Block(UserId blocker, UserId blocked, Instant at) {}
record Mute(UserId muter, UserId muted, String keyword, Instant expiresAt) {}
record UserList(long listId, UserId owner, String name, boolean isPrivate, long memberCount) {}

// ---------- Engagement ----------
record Like(TweetId tweet, UserId user, Instant at) {}
record Bookmark(UserId user, TweetId tweet, Instant at) {}
record PollVote(long pollId, UserId user, int option, Instant at) {}

// ---------- Notifications (sealed variants) ----------
sealed interface NotificationPayload permits LikeN, ReplyN, MentionN, FollowN, RetweetN, CelebrityTweetN {}
record LikeN(TweetId tweet, List<UserId> actors, long totalActors) implements NotificationPayload {}
record ReplyN(TweetId reply, UserId actor) implements NotificationPayload {}
record MentionN(TweetId tweet, UserId actor) implements NotificationPayload {}
record FollowN(UserId actor) implements NotificationPayload {}
record RetweetN(TweetId tweet, List<UserId> actors, long totalActors) implements NotificationPayload {}
record CelebrityTweetN(UserId celebrity, TweetId tweet) implements NotificationPayload {}

record Notification(long id, UserId recipient, NotificationPayload payload,
                    boolean read, Instant createdAt) {}
record CelebritySubscription(UserId subscriber, UserId celebrity, Set<Channel> channels, Instant since) {}
enum Channel { IN_APP, PUSH, EMAIL, SMS }
record Device(long deviceId, UserId owner, Platform platform, String pushToken, Instant lastSeen) {}
enum Platform { IOS, ANDROID, WEB }

// ---------- Direct messages ----------
record Conversation(long id, ConversationType type, Set<UserId> members, Instant createdAt) {}
enum ConversationType { ONE_TO_ONE, GROUP }
record DirectMessage(long conversationId, long messageId, UserId sender, String ciphertextOrBody,
                     MediaRef media, Instant sentAt) {}

// ---------- Events (Kafka payloads) ----------
sealed interface DomainEvent permits TweetCreated, TweetDeleted, TweetLiked, UserFollowed,
                                    UserUnfollowed, CelebrityFlagChanged {
    String eventId();            // UUID, used for consumer idempotency
    Instant occurredAt();
}
record TweetCreated(String eventId, Instant occurredAt, TweetId tweet, UserId author,
                    TweetKind kind, TweetId refersTo, List<UserId> mentioned) implements DomainEvent {}
record TweetDeleted(String eventId, Instant occurredAt, TweetId tweet, UserId author) implements DomainEvent {}
record TweetLiked(String eventId, Instant occurredAt, TweetId tweet, UserId user, boolean undo) implements DomainEvent {}
record UserFollowed(String eventId, Instant occurredAt, UserId follower, UserId followee) implements DomainEvent {}
record UserUnfollowed(String eventId, Instant occurredAt, UserId follower, UserId followee) implements DomainEvent {}
record CelebrityFlagChanged(String eventId, Instant occurredAt, UserId user, boolean celebrity) implements DomainEvent {}
enum TweetKind { ORIGINAL, REPLY, RETWEET, QUOTE }
```

**Modeling notes**

- A retweet is a lightweight row that points to the original and never copies its body, so edits and deletes of the original apply everywhere.
- Counters are separate objects from the tweet because they change at a very different rate and are cached separately.
- `conversationId` on every reply makes the whole thread one partition query, regardless of reply depth.
- Notification payloads are a sealed type, so the compiler forces every renderer to handle each kind.

## A2. Entity relationships

&#91;embedded content: entity relationships · 11 entities around User and Tweet\]

A tweet also points back to another tweet for replies, retweets and quotes (a self-reference through `in_reply_to`, `retweet_of` and `quote_of`). Direct messages, conversations and notification preferences hang off the user in the same way and are modelled in A3 and A4.

## A3. PostgreSQL schema (accounts and low-volume relational data)

PostgreSQL holds data that is small, relational and needs transactions or uniqueness guarantees. It is sharded by `user_id` (Citus or Vitess-style) and read through replicas. Timelines, tweets and engagement never live here.

```sql
-- ============ Accounts ============
CREATE TABLE users (
  user_id        BIGINT PRIMARY KEY,                 -- Snowflake
  handle         VARCHAR(15)  NOT NULL,
  handle_lower   VARCHAR(15)  NOT NULL UNIQUE,       -- case-insensitive uniqueness
  display_name   VARCHAR(50)  NOT NULL,
  email          VARCHAR(254) UNIQUE,
  email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
  phone_hash     CHAR(64)     UNIQUE,                -- hashed, never raw
  bio            VARCHAR(160),
  location       VARCHAR(30),
  avatar_key     VARCHAR(255),
  banner_key     VARCHAR(255),
  visibility     SMALLINT     NOT NULL DEFAULT 0,    -- 0 public, 1 protected
  verified_type  SMALLINT     NOT NULL DEFAULT 0,
  status         SMALLINT     NOT NULL DEFAULT 0,    -- 0 active, 1 suspended, 2 deactivated, 3 locked
  is_celebrity   BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  version        INT          NOT NULL DEFAULT 0     -- optimistic locking
);
CREATE INDEX idx_users_status ON users(status) WHERE status <> 0;

CREATE TABLE user_credentials (
  user_id       BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
  password_hash VARCHAR(255) NOT NULL,               -- argon2id
  mfa_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
  mfa_secret    BYTEA,                               -- encrypted with KMS data key
  password_changed_at TIMESTAMPTZ
);

CREATE TABLE user_counters (                         -- periodically reconciled from Cassandra/Kafka
  user_id   BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
  followers BIGINT NOT NULL DEFAULT 0,
  following BIGINT NOT NULL DEFAULT 0,
  tweets    BIGINT NOT NULL DEFAULT 0,
  listed    BIGINT NOT NULL DEFAULT 0
);

-- ============ Auth and sessions ============
CREATE TABLE oauth_clients (
  client_id     VARCHAR(64) PRIMARY KEY,
  owner_user_id BIGINT REFERENCES users(user_id),
  name          VARCHAR(100) NOT NULL,
  redirect_uris TEXT[] NOT NULL,
  scopes        TEXT[] NOT NULL,
  secret_hash   VARCHAR(255),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  token_hash  CHAR(64) PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  client_id   VARCHAR(64) NOT NULL REFERENCES oauth_clients(client_id),
  device_id   BIGINT,
  issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ
);
CREATE INDEX idx_rt_user ON refresh_tokens(user_id) WHERE revoked_at IS NULL;

-- ============ Devices and preferences ============
CREATE TABLE devices (
  device_id  BIGINT PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  platform   SMALLINT NOT NULL,                      -- 0 ios, 1 android, 2 web
  push_token VARCHAR(512),
  app_version VARCHAR(20),
  last_seen  TIMESTAMPTZ,
  UNIQUE (user_id, push_token)
);
CREATE INDEX idx_devices_user ON devices(user_id);

CREATE TABLE notification_prefs (
  user_id       BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
  push_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
  email_digest  VARCHAR(10) NOT NULL DEFAULT 'daily',  -- off, daily, weekly
  quiet_start   SMALLINT,                             -- minute of day, user timezone
  quiet_end     SMALLINT,
  timezone      VARCHAR(40) NOT NULL DEFAULT 'UTC',
  filters       JSONB NOT NULL DEFAULT '{}'           -- only people I follow, hide low quality, etc.
);

-- Celebrity bell subscriptions are NOT stored here: one celebrity can have millions of
-- subscribers, so they live in Cassandra (see A4).

-- ============ Lists ============
CREATE TABLE user_lists (
  list_id      BIGINT PRIMARY KEY,
  owner_id     BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  name         VARCHAR(25) NOT NULL,
  description  VARCHAR(100),
  is_private   BOOLEAN NOT NULL DEFAULT FALSE,
  member_count INT NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lists_owner ON user_lists(owner_id);

CREATE TABLE list_members (
  list_id   BIGINT NOT NULL REFERENCES user_lists(list_id) ON DELETE CASCADE,
  member_id BIGINT NOT NULL,
  added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (list_id, member_id)
);
CREATE INDEX idx_list_members_member ON list_members(member_id);

-- ============ Moderation and audit ============
CREATE TABLE reports (
  report_id    BIGINT PRIMARY KEY,
  reporter_id  BIGINT NOT NULL,
  target_type  SMALLINT NOT NULL,                      -- 0 tweet, 1 user, 2 dm
  target_id    BIGINT NOT NULL,
  reason       VARCHAR(30) NOT NULL,
  status       SMALLINT NOT NULL DEFAULT 0,            -- 0 open, 1 reviewing, 2 actioned, 3 dismissed
  priority     INT NOT NULL DEFAULT 0,                 -- weighted by reach
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reports_queue ON reports(status, priority DESC, created_at);

CREATE TABLE moderation_actions (
  action_id   BIGINT PRIMARY KEY,
  target_type SMALLINT NOT NULL,
  target_id   BIGINT NOT NULL,
  action      VARCHAR(20) NOT NULL,                    -- warn, shadow_ban, suspend, remove_tweet
  actor       VARCHAR(64) NOT NULL,                    -- reviewer id or 'auto:<model>'
  reason      TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);                     -- monthly partitions, append-only audit

-- ============ Transactional outbox (for services that use PostgreSQL) ============
CREATE TABLE outbox (
  id           BIGSERIAL PRIMARY KEY,
  aggregate_id BIGINT NOT NULL,
  topic        VARCHAR(80) NOT NULL,
  msg_key      VARCHAR(64) NOT NULL,
  payload      JSONB NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;
```

**Design notes**

- Handles are unique case-insensitively through `handle_lower`; a rename writes a tombstone row to a `handle_history` table so old handles cannot be hijacked immediately.
- Counters in PostgreSQL are a periodically reconciled copy. The live counts come from Redis and Cassandra.
- Raw secrets (MFA seeds, tokens) are stored encrypted or hashed; PII (email, phone) is covered by field-level encryption and a deletion workflow.

## A4. Cassandra schema (high-volume, query-first data)

Every table below answers one query. The **partition key** decides distribution and must keep partitions under roughly 100 MB or 100k rows; the **clustering key** decides sort order. IDs are Snowflake, so `DESC` clustering on an ID means newest first. Replication is `NetworkTopologyStrategy` with RF 3 per region; reads and writes use `LOCAL_QUORUM`.

```sql
-- ============ User-defined types ============
CREATE TYPE entities (
  hashtags LIST<FROZEN<tuple<text,int,int>>>,           -- tag, start, end
  mentions LIST<FROZEN<tuple<bigint,text,int,int>>>,    -- user_id, handle, start, end
  urls     LIST<FROZEN<tuple<text,text,int,int>>>       -- expanded, short code, start, end
);
CREATE TYPE media_ref (media_id bigint, type tinyint, object_key text, width int, height int, alt text);

-- ============ Tweets ============
CREATE TABLE tweets (
  tweet_id        bigint PRIMARY KEY,
  author_id       bigint,
  kind            tinyint,                 -- 0 original, 1 reply, 2 retweet, 3 quote
  body            text,
  entities        FROZEN<entities>,
  media           LIST<FROZEN<media_ref>>,
  poll_id         bigint,
  in_reply_to     bigint,
  in_reply_to_user bigint,
  conversation_id bigint,
  retweet_of      bigint,
  quote_of        bigint,
  lang            text,
  source          text,
  edit_version    int,
  deleted         boolean,
  created_at      timestamp
) WITH compaction = {'class':'TimeWindowCompactionStrategy'} AND gc_grace_seconds = 864000;

CREATE TABLE tweet_edits (                          -- edit history, rendered on demand
  tweet_id bigint, version int, body text, edited_at timestamp,
  PRIMARY KEY (tweet_id, version)
) WITH CLUSTERING ORDER BY (version DESC);

-- Profile timeline: one partition per author per month (bounds partition size)
CREATE TABLE user_tweets (
  author_id bigint, bucket text, tweet_id bigint, kind tinyint,
  PRIMARY KEY ((author_id, bucket), tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);

-- Thread / conversation view
CREATE TABLE conversation_tweets (
  conversation_id bigint, tweet_id bigint, parent_id bigint, author_id bigint,
  PRIMARY KEY (conversation_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id ASC);

-- Hashtag timeline: day buckets keep trending tags from creating giant partitions
CREATE TABLE hashtag_tweets (
  tag text, day date, tweet_id bigint,
  PRIMARY KEY ((tag, day), tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC) AND default_time_to_live = 2592000;

-- ============ Timelines ============
-- Durable copy of the pushed part of the home timeline (Redis is the hot copy)
CREATE TABLE home_timeline (
  user_id bigint, tweet_id bigint, author_id bigint, reason tinyint,   -- reason: follow, list, recommended
  PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC) AND default_time_to_live = 1209600;

-- Mentions timeline
CREATE TABLE mentions_timeline (
  user_id bigint, tweet_id bigint, author_id bigint,
  PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC) AND default_time_to_live = 7776000;

-- ============ Social graph (both directions, written by one batch) ============
CREATE TABLE followers (
  user_id bigint, follower_id bigint, since timestamp, notify boolean,
  PRIMARY KEY (user_id, follower_id)                   -- paged scan; celebrity lists never loaded whole
);
CREATE TABLE following (
  user_id bigint, followee_id bigint, since timestamp,
  PRIMARY KEY (user_id, followee_id)
);
CREATE TABLE follow_requests (
  target_id bigint, requester_id bigint, requested_at timestamp,
  PRIMARY KEY (target_id, requester_id)
);
CREATE TABLE blocks (blocker_id bigint, blocked_id bigint, at timestamp,
  PRIMARY KEY (blocker_id, blocked_id));
CREATE TABLE blocked_by (blocked_id bigint, blocker_id bigint,
  PRIMARY KEY (blocked_id, blocker_id));              -- reverse lookup for fan-out filtering
CREATE TABLE mutes (muter_id bigint, muted_id bigint, keyword text, expires_at timestamp,
  PRIMARY KEY (muter_id, muted_id, keyword));

-- Celebrity bell subscriptions live here, not in PostgreSQL: one celebrity can have millions of rows
CREATE TABLE celebrity_subscriptions (
  celebrity_id bigint, subscriber_id bigint, channels tinyint, created_at timestamp,
  PRIMARY KEY (celebrity_id, subscriber_id)
);
CREATE TABLE subscriptions_by_user (
  subscriber_id bigint, celebrity_id bigint,
  PRIMARY KEY (subscriber_id, celebrity_id)
);

-- ============ Engagement ============
CREATE TABLE tweet_likes (            -- who liked this tweet (idempotent upsert)
  tweet_id bigint, user_id bigint, at timestamp,
  PRIMARY KEY (tweet_id, user_id)
);
CREATE TABLE user_likes (             -- a user's Likes tab, newest first
  user_id bigint, tweet_id bigint, at timestamp,
  PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);
CREATE TABLE retweeters (tweet_id bigint, user_id bigint, retweet_id bigint,
  PRIMARY KEY (tweet_id, user_id));
CREATE TABLE user_retweets (user_id bigint, original_id bigint, retweet_id bigint,
  PRIMARY KEY (user_id, original_id));               -- 'did I retweet this?' during hydration
CREATE TABLE bookmarks (user_id bigint, tweet_id bigint, at timestamp,
  PRIMARY KEY (user_id, tweet_id)) WITH CLUSTERING ORDER BY (tweet_id DESC);

CREATE TABLE tweet_counters (         -- counter tables hold ONLY counter columns
  tweet_id bigint PRIMARY KEY,
  likes counter, retweets counter, quotes counter,
  replies counter, bookmarks counter, views counter
);

CREATE TABLE polls (poll_id bigint PRIMARY KEY, tweet_id bigint,
  options list<text>, ends_at timestamp);
CREATE TABLE poll_votes (poll_id bigint, user_id bigint, option int,
  PRIMARY KEY (poll_id, user_id));                    -- one vote per user
CREATE TABLE poll_results (poll_id bigint, option int, votes counter,
  PRIMARY KEY (poll_id, option));

-- ============ Notifications ============
CREATE TABLE notifications (
  user_id bigint, bucket text, notif_id timeuuid,
  kind tinyint, payload text, actors list<bigint>, actor_count int, tweet_id bigint, is_read boolean,
  PRIMARY KEY ((user_id, bucket), notif_id)
) WITH CLUSTERING ORDER BY (notif_id DESC) AND default_time_to_live = 7776000;

-- Aggregation window so 'Ana and 40 others liked' is one row, not 41
CREATE TABLE notification_aggregates (
  user_id bigint, kind tinyint, tweet_id bigint, window_start timestamp,
  actors list<bigint>, total int,
  PRIMARY KEY ((user_id, kind), tweet_id, window_start)
) WITH default_time_to_live = 86400;

-- Dedup for alert fan-out: one alert per (user, tweet)
CREATE TABLE alert_sent (user_id bigint, tweet_id bigint,
  PRIMARY KEY (user_id, tweet_id)) WITH default_time_to_live = 172800;

-- ============ Direct messages ============
CREATE TABLE conversation_members (
  conversation_id bigint, user_id bigint, role tinyint, joined_at timestamp, last_read_msg bigint,
  PRIMARY KEY (conversation_id, user_id)
);
CREATE TABLE conversations_by_user (
  user_id bigint, last_activity timeuuid, conversation_id bigint, preview text, unread int,
  PRIMARY KEY (user_id, last_activity, conversation_id)
) WITH CLUSTERING ORDER BY (last_activity DESC, conversation_id ASC);
CREATE TABLE messages (
  conversation_id bigint, bucket text, message_id bigint,
  sender_id bigint, body blob, media FROZEN<media_ref>, sent_at timestamp, deleted boolean,
  PRIMARY KEY ((conversation_id, bucket), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);       -- body is ciphertext when E2EE is on

-- ============ Media metadata ============
CREATE TABLE media (
  media_id bigint PRIMARY KEY, owner_id bigint, type tinyint, state tinyint,
  object_key text, variants map<text,text>, width int, height int, duration_ms int,
  moderation tinyint, created_at timestamp
);

-- ============ Outbox for Cassandra-backed services ============
CREATE TABLE outbox (
  shard int, id timeuuid, topic text, msg_key text, payload text,
  PRIMARY KEY (shard, id)
) WITH default_time_to_live = 604800;
```

**Design notes**

- **Denormalisation is deliberate.** `followers`/`following`, `tweet_likes`/`user_likes`, and `blocks`/`blocked_by` store the same fact twice so each read is a single partition lookup. They are written together in one logged batch or through the outbox.
- **Bucketing** (`bucket` = month or day) caps partition growth for timelines, notifications and messages; readers walk buckets from newest to oldest until the page is full.
- **Counter tables** accept only increments; they are fed by batched flushes from Redis, never one event at a time.
- **Tombstones:** deletes are soft (`deleted = true`) for tweets; TTL tables use TimeWindowCompaction so expiry drops whole SSTables.
- **Hot partitions:** a celebrity's `followers` partition is huge, so it is only scanned by paged, token-aware readers and never by user-facing requests.

## Appendix A. Data and object modeling

This appendix is the complete data model: the Java 21 domain objects, how they relate, and the exact schema in each store. The rule behind every table is **model for the query**: each read path in the product gets a table or key shaped to answer it with one partition lookup.

### A.1 Domain object model (Java 21 records)

Immutable records and enums keep the model compact. The same types serve as service DTOs, Kafka payloads (via Avro/Protobuf mappings) and Cassandra row types.

```java
// ---------- Identity ----------
enum AccountStatus { ACTIVE, SUSPENDED, DEACTIVATED, DELETED }

record User(long id, String handle, String displayName, String email, String passwordHash,
            AccountStatus status, boolean verified, boolean protectedAccount,
            String locale, Instant createdAt) {}

record Profile(long userId, String bio, String location, URI website,
               String avatarKey, String bannerKey, Instant updatedAt) {}

record UserCounters(long userId, long followers, long following, long tweets,
                    boolean celebrity) {}               // celebrity = followers >= threshold

// ---------- Content ----------
enum TweetKind { ORIGINAL, REPLY, QUOTE, RETWEET }
enum Visibility { PUBLIC, FOLLOWERS, MENTIONED }

record Tweet(long id, long authorId, TweetKind kind, String text,
             Long inReplyToId, Long conversationId,   // conversationId = root tweet of a thread
             Long quotedId, Long retweetOfId,
             List<Long> mediaIds, List<String> hashtags, List<Long> mentionedUserIds,
             Visibility visibility, String lang, int version,
             Instant createdAt, Instant editedAt, boolean deleted) {}

record TweetCounters(long tweetId, long likes, long retweets, long quotes,
                     long replies, long bookmarks, long views) {}

enum MediaType { IMAGE, GIF, VIDEO }
enum ProcessingState { UPLOADED, PROCESSING, READY, REJECTED }
record Media(long id, long ownerId, MediaType type, String objectKey,
             int width, int height, int durationMs, ProcessingState state, String altText) {}

record Poll(long tweetId, List<String> options, Instant endsAt) {}
record PollVote(long tweetId, long userId, int optionIndex, Instant at) {}

// ---------- Relationships and engagement ----------
enum FollowState { ACTIVE, PENDING }
record Follow(long followerId, long followeeId, FollowState state, Instant since) {}
record Block(long userId, long blockedId, Instant at) {}
record Mute(long userId, long mutedId, String keyword, Instant expiresAt) {}
record Like(long tweetId, long userId, Instant at) {}
record Bookmark(long userId, long tweetId, Instant at) {}
record UserList(long id, long ownerId, String name, boolean isPrivate) {}
record ListMember(long listId, long userId, Instant addedAt) {}

// ---------- Notifications and delivery ----------
enum NotificationType { LIKE, REPLY, MENTION, RETWEET, QUOTE, FOLLOW, CELEBRITY_TWEET }
record Notification(long userId, long id, NotificationType type, long actorId, Long tweetId,
                    int aggregateCount, boolean read, Instant at) {}

enum Platform { IOS, ANDROID, WEB }
record DeviceToken(long userId, String token, Platform platform, Instant lastSeen) {}

enum AlertChannel { PUSH, IN_APP, EMAIL }
record AlertSubscription(long celebrityId, long subscriberId, Set<AlertChannel> channels,
                         Instant since) {}

// ---------- Messaging ----------
record Conversation(long id, boolean group, List<Long> memberIds, Instant createdAt) {}
record DirectMessage(long conversationId, long messageId, long senderId,
                     byte[] ciphertext, Instant at) {}

// ---------- Read model returned by APIs (hydrated) ----------
record AuthorSummary(long id, String handle, String displayName, String avatarKey, boolean verified) {}
record ViewerFlags(boolean liked, boolean retweeted, boolean bookmarked, boolean followsAuthor) {}
record TweetView(Tweet tweet, AuthorSummary author, TweetCounters counters,
                 List<Media> media, ViewerFlags viewer) {}
record TimelinePage(List<TweetView> items, Long nextCursor) {}
```

### A.2 Entity relationships

| Relationship | Cardinality | How it is stored |
| --- | --- | --- |
| User - Profile | 1 : 1 | Same PostgreSQL shard, same `user_id` |
| User - Tweet (author) | 1 : N | `tweets.author_id` and the `user_tweets` timeline table |
| Tweet - Tweet (reply) | N : 1 | `in_reply_to_id` plus `conversation_id`; thread read from `conversation_tweets` |
| Tweet - Tweet (quote, retweet) | N : 1 | `quoted_id` / `retweet_of_id` on the new tweet; originals are never copied |
| User - User (follow) | M : N | Two query tables: `followers` and `following`, written together |
| User - User (block, mute) | M : N | PostgreSQL; cached as a Redis set per user for read-time filtering |
| User - Tweet (like, bookmark) | M : N | `tweet_likes` (by tweet), `user_likes` (by user), `bookmarks` (by user) |
| Tweet - Media | 1 : N | `media_ids` on the tweet; media rows in PostgreSQL, bytes in object storage |
| Tweet - Hashtag / Mention | M : N | Parsed on write; `hashtag_tweets` table and Elasticsearch fields |
| Celebrity - Subscriber (alerts) | M : N | `alert_subscribers` partitioned by celebrity and bucket |
| User - Notification | 1 : N | `notifications` partitioned by user, newest first |
| User - Device | 1 : N | `device_tokens` by user; fan-out of push uses the token set |
| Conversation - Message | 1 : N | `messages` partitioned by conversation and day bucket |

### A.3 Cassandra schema (high-volume, write-heavy data)

Cassandra tables are designed from the query backwards. This map shows which table answers which API call.

| API / query | Table | Partition key | Clustering |
| --- | --- | --- | --- |
| Get tweet by ID | `tweets` | `tweet_id` | - |
| Profile timeline | `user_tweets` | `(author_id, bucket)` | `tweet_id DESC` |
| Home timeline (durable copy) | `home_timeline` | `user_id` | `tweet_id DESC` |
| Thread / replies | `conversation_tweets` | `conversation_id` | `tweet_id ASC` |
| Followers page | `followers` | `(user_id, shard)` | `follower_id` |
| Following page | `following` | `user_id` | `followee_id` |
| Who liked a tweet | `tweet_likes` | `(tweet_id, shard)` | `user_id` |
| Did I like it / my likes | `user_likes` | `user_id` | `tweet_id DESC` |
| Bookmarks | `bookmarks` | `user_id` | `tweet_id DESC` |
| Tweets for a hashtag | `hashtag_tweets` | `(tag, bucket)` | `tweet_id DESC` |
| Notifications inbox | `notifications` | `user_id` | `notification_id DESC` |
| Alert subscribers of a celebrity | `alert_subscribers` | `(celebrity_id, bucket)` | `subscriber_id` |
| Chat history | `messages` | `(conversation_id, day)` | `message_id DESC` |

```sql
CREATE KEYSPACE social WITH replication = {
  'class': 'NetworkTopologyStrategy', 'dc-east': 3, 'dc-west': 3, 'dc-eu': 3
} AND durable_writes = true;

-- ===== Tweets =====
CREATE TABLE social.tweets (
  tweet_id         bigint PRIMARY KEY,         -- Snowflake
  author_id        bigint,
  kind             tinyint,                    -- 0 original, 1 reply, 2 quote, 3 retweet
  body             text,
  in_reply_to_id   bigint,
  conversation_id  bigint,
  quoted_id        bigint,
  retweet_of_id    bigint,
  media_ids        list<bigint>,
  hashtags         set<text>,
  mentioned_ids    set<bigint>,
  visibility       tinyint,
  lang             text,
  version          int,
  created_at       timestamp,
  edited_at        timestamp,
  deleted          boolean
) WITH compaction = {'class': 'TimeWindowCompactionStrategy',
                     'compaction_window_unit': 'DAYS', 'compaction_window_size': 1}
  AND gc_grace_seconds = 864000;

CREATE TABLE social.user_tweets (            -- profile timeline, one partition per author per month
  author_id bigint, bucket text, tweet_id bigint, kind tinyint,
  PRIMARY KEY ((author_id, bucket), tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);

CREATE TABLE social.conversation_tweets (    -- replies grouped under the root tweet
  conversation_id bigint, tweet_id bigint, author_id bigint, in_reply_to_id bigint,
  PRIMARY KEY (conversation_id, tweet_id)
);

CREATE TABLE social.home_timeline (          -- durable backup of the Redis timeline
  user_id bigint, tweet_id bigint, author_id bigint,
  PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC)
  AND default_time_to_live = 1209600;        -- 14 days

-- ===== Social graph (both directions, sharded for celebrities) =====
CREATE TABLE social.followers (              -- shard = follower_id % 64 spreads a 50M partition
  user_id bigint, shard smallint, follower_id bigint, since timestamp, active_at timestamp,
  PRIMARY KEY ((user_id, shard), follower_id)
);
CREATE TABLE social.following (
  user_id bigint, followee_id bigint, since timestamp, is_celebrity boolean,
  PRIMARY KEY (user_id, followee_id)
);
CREATE TABLE social.follow_requests (        -- protected accounts
  target_id bigint, requester_id bigint, requested_at timestamp,
  PRIMARY KEY (target_id, requester_id)
);

-- ===== Engagement =====
CREATE TABLE social.tweet_likes (            -- idempotent: same row on a repeated like
  tweet_id bigint, shard smallint, user_id bigint, at timestamp,
  PRIMARY KEY ((tweet_id, shard), user_id)
);
CREATE TABLE social.user_likes (
  user_id bigint, tweet_id bigint, at timestamp,
  PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);
CREATE TABLE social.bookmarks (
  user_id bigint, tweet_id bigint, at timestamp,
  PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);
CREATE TABLE social.tweet_counters (         -- flushed from Redis every few seconds
  tweet_id bigint PRIMARY KEY,
  likes counter, retweets counter, quotes counter, replies counter, bookmarks counter, views counter
);
CREATE TABLE social.user_counters (
  user_id bigint PRIMARY KEY,
  followers counter, following counter, tweets counter
);
CREATE TABLE social.poll_votes (
  tweet_id bigint, user_id bigint, option_index tinyint, at timestamp,
  PRIMARY KEY (tweet_id, user_id)
);

-- ===== Discovery =====
CREATE TABLE social.hashtag_tweets (
  tag text, bucket text, tweet_id bigint,    -- bucket = yyyyMMdd
  PRIMARY KEY ((tag, bucket), tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC) AND default_time_to_live = 2592000;
CREATE TABLE social.list_members (
  list_id bigint, user_id bigint, added_at timestamp,
  PRIMARY KEY (list_id, user_id)
);

-- ===== Notifications and alerts =====
CREATE TABLE social.notifications (
  user_id bigint, notification_id bigint, type tinyint, actor_ids list<bigint>,
  tweet_id bigint, aggregate_count int, is_read boolean, at timestamp,
  PRIMARY KEY (user_id, notification_id)
) WITH CLUSTERING ORDER BY (notification_id DESC) AND default_time_to_live = 7776000;
CREATE TABLE social.alert_subscribers (      -- users who tapped the bell on a celebrity
  celebrity_id bigint, bucket smallint, subscriber_id bigint,
  channels set<tinyint>, since timestamp,
  PRIMARY KEY ((celebrity_id, bucket), subscriber_id)
);
CREATE TABLE social.device_tokens (
  user_id bigint, token text, platform tinyint, last_seen timestamp,
  PRIMARY KEY (user_id, token)
) WITH default_time_to_live = 5184000;

-- ===== Messaging =====
CREATE TABLE social.messages (
  conversation_id bigint, day int, message_id bigint, sender_id bigint,
  ciphertext blob, at timestamp,
  PRIMARY KEY ((conversation_id, day), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
CREATE TABLE social.user_conversations (     -- inbox ordering
  user_id bigint, last_message_id bigint, conversation_id bigint, unread int,
  PRIMARY KEY (user_id, last_message_id, conversation_id)
) WITH CLUSTERING ORDER BY (last_message_id DESC, conversation_id ASC);

-- ===== Reliability =====
CREATE TABLE social.outbox (                 -- transactional outbox, drained by a relay
  shard smallint, event_id bigint, topic text, event_key text, payload text,
  PRIMARY KEY (shard, event_id)
) WITH default_time_to_live = 604800;
```

**Design notes**

- **Bucketing** (`bucket` by month or day) keeps partitions below roughly 100 MB and 100k rows.
- **Sharded partitions** (`followers`, `tweet_likes`, `alert_subscribers`) are essential for celebrities: a 50M-row partition would be a hot spot, so the key includes a shard number and readers fan out across shards in parallel, paged by cursor.
- **TTL** on derived data (timelines, notifications, hashtags, tokens) bounds storage without cleanup jobs.
- **TimeWindowCompaction** for tweets matches the append-only, time-ordered write pattern.
- **Counters** live in separate tables because Cassandra counter columns cannot share a table with regular columns.
- **Deletes** are soft first (`deleted = true`) because Cassandra tombstones are expensive at this scale; physical removal happens through TTL and compaction.

### A.4 PostgreSQL schema (accounts, settings, moderation)

Data that needs transactions, uniqueness and joins stays relational. It is sharded by `user_id` (Citus or Vitess) with read replicas; Snowflake IDs are used as primary keys so shards never collide.

```sql
CREATE TABLE users (
  id             BIGINT PRIMARY KEY,                  -- Snowflake
  handle         VARCHAR(15)  NOT NULL,
  handle_lower   VARCHAR(15)  GENERATED ALWAYS AS (lower(handle)) STORED,
  display_name   VARCHAR(50)  NOT NULL,
  email          VARCHAR(254) NOT NULL,
  password_hash  TEXT,                                -- argon2id; null for SSO-only accounts
  status         SMALLINT     NOT NULL DEFAULT 0,     -- 0 active, 1 suspended, 2 deactivated, 3 deleted
  verified       BOOLEAN      NOT NULL DEFAULT FALSE,
  protected      BOOLEAN      NOT NULL DEFAULT FALSE,
  locale         VARCHAR(10)  NOT NULL DEFAULT 'en',
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (handle_lower),
  UNIQUE (email)
);

CREATE TABLE profiles (
  user_id     BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  bio         VARCHAR(160),
  location    VARCHAR(30),
  website     TEXT,
  avatar_key  TEXT,
  banner_key  TEXT,
  pinned_tweet_id BIGINT,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credentials_mfa (
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type        SMALLINT NOT NULL,                      -- totp, sms, passkey
  secret_enc  BYTEA NOT NULL,                         -- envelope-encrypted via KMS
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, type)
);

CREATE TABLE refresh_tokens (
  token_hash  BYTEA PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id   TEXT,
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ
);
CREATE INDEX ix_refresh_user ON refresh_tokens (user_id);

CREATE TABLE blocks (
  user_id BIGINT NOT NULL, blocked_id BIGINT NOT NULL, at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, blocked_id)
);
CREATE INDEX ix_blocks_reverse ON blocks (blocked_id, user_id);   -- 'who blocked me' checks

CREATE TABLE mutes (
  user_id BIGINT NOT NULL, muted_id BIGINT, keyword TEXT, expires_at TIMESTAMPTZ,
  PRIMARY KEY (user_id, muted_id, keyword)
);

CREATE TABLE user_settings (
  user_id BIGINT PRIMARY KEY,
  notify_push BOOLEAN DEFAULT TRUE, notify_email BOOLEAN DEFAULT TRUE,
  quiet_hours_start SMALLINT, quiet_hours_end SMALLINT,
  timezone VARCHAR(40), sensitive_media BOOLEAN DEFAULT FALSE
);

CREATE TABLE lists (
  id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, name VARCHAR(25) NOT NULL,
  is_private BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_lists_owner ON lists (owner_id);

CREATE TABLE media (
  id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, type SMALLINT NOT NULL,
  object_key TEXT NOT NULL, width INT, height INT, duration_ms INT,
  state SMALLINT NOT NULL DEFAULT 0, alt_text VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_media_owner ON media (owner_id, created_at DESC);

CREATE TABLE polls (
  tweet_id BIGINT PRIMARY KEY, options JSONB NOT NULL, ends_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE reports (                                -- moderation queue
  id BIGINT PRIMARY KEY, reporter_id BIGINT NOT NULL, tweet_id BIGINT, user_id BIGINT,
  reason SMALLINT NOT NULL, state SMALLINT NOT NULL DEFAULT 0,
  priority INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reports_queue ON reports (state, priority DESC, created_at);

CREATE TABLE moderation_actions (                     -- append-only audit trail
  id BIGINT PRIMARY KEY, target_user_id BIGINT, target_tweet_id BIGINT,
  action SMALLINT NOT NULL, actor TEXT NOT NULL, reason TEXT, at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scheduled_tweets (
  id BIGINT PRIMARY KEY, author_id BIGINT NOT NULL, body TEXT NOT NULL,
  media_ids BIGINT[], publish_at TIMESTAMPTZ NOT NULL, state SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_sched_due ON scheduled_tweets (publish_at) WHERE state = 0;
```

### A.5 Redis key design (hot data)

Redis holds only data that can be rebuilt from Cassandra or PostgreSQL. Hash tags `{...}` keep related keys on the same cluster slot.

| Key pattern | Type | Content | TTL / bound |
| --- | --- | --- | --- |
| `tl:{userId}` | List | Home timeline tweet IDs, newest first | Capped at 800; 7 days idle expiry |
| `tw:{tweetId}` | Hash or compact JSON | Tweet body and metadata | 6 h, jittered |
| `utl:{authorId}` | Sorted set | Latest 200 tweet IDs of an author (the celebrity pull source) | Capped; no expiry for celebrities |
| `cnt:{tweetId}:{shard}` | String (counter) | Likes, retweets, replies, views shards | Flushed every few seconds |
| `celeb:follows:{userId}` | Set | Celebrity IDs a user follows | Invalidated on follow or unfollow |
| `celeb:flag:{userId}` | String | 1 if the account is above the celebrity threshold | Long TTL |
| `blk:{userId}` | Set | Blocked and muted user IDs for read-time filtering | 1 h, refreshed on change |
| `liked:{userId}:{bucket}` | Bloom filter | Probable likes, to skip a DB check | Rebuilt daily |
| `sess:{sessionId}` | Hash | Session and device info | Sliding 30 days |
| `idem:{userId}:{key}` | String | Tweet ID for an idempotent create | 24 h |
| `rl:{scope}:{id}` | Hash | Token bucket state | 2x refill window |
| `rt:conn:{userId}` | Set | Realtime gateways holding this user's connections | Connection lifetime |
| `trend:{region}` | Sorted set | Trending topics with scores | Refreshed every minute |
| `sug:{userId}` | List | Who-to-follow suggestions | 24 h |
| `unread:{userId}` | String | Unread notification count | Reset on read |

### A.6 Elasticsearch index mapping

Tweets go into daily or weekly indices (`tweets-2026.10.17`) behind an alias, so old indices roll to warm and cold tiers and are dropped by retention. The index is a derived view, rebuilt by replaying Kafka.

```json
PUT _index_template/tweets
{
  "index_patterns": ["tweets-*"],
  "template": {
    "settings": { "number_of_shards": 24, "number_of_replicas": 1, "refresh_interval": "2s" },
    "mappings": {
      "dynamic": "strict",
      "properties": {
        "tweet_id":   { "type": "long" },
        "author_id":  { "type": "long" },
        "handle":     { "type": "keyword" },
        "text":       { "type": "text", "analyzer": "standard",
                        "fields": { "ngram": { "type": "text", "analyzer": "edge_ngram_analyzer" } } },
        "hashtags":   { "type": "keyword" },
        "mentions":   { "type": "long" },
        "lang":       { "type": "keyword" },
        "kind":       { "type": "keyword" },
        "has_media":  { "type": "boolean" },
        "created_at": { "type": "date" },
        "engagement": { "type": "rank_feature" },
        "visibility": { "type": "keyword" },
        "deleted":    { "type": "boolean" }
      }
    }
  }
}
```

Separate small indices serve `users` (handle and display name with edge n-grams for typeahead) and `hashtags`. Tweet search always filters `deleted:false` and `visibility:PUBLIC`, and results are re-checked against block lists at read time.

### A.7 Kafka topics and event schemas

Events are defined in Avro or Protobuf and registered in a schema registry with backward compatibility. Keys decide ordering: events for the same key land on the same partition.

| Topic | Key | Partitions | Retention | Producers | Consumers |
| --- | --- | --- | --- | --- | --- |
| `tweet-created` | `author_id` | 256 | 7 days | Outbox relay | Fan-out, Search indexer, Notifications, Trends, Moderation |
| `tweet-deleted` | `tweet_id` | 64 | 7 days | Tweet Service | Timeline cleaner, Search, Counters |
| `engagement-events` | `tweet_id` | 256 | 3 days | Engagement Service | Counter aggregator, Notifications, Ranking features |
| `follow-events` | `followee_id` | 128 | 7 days | Graph Service | Counters, Notifications, Celebrity classifier, Backfill |
| `celebrity-alerts` | `celebrity_id#page` | 512 | 1 day | Notification Service | Alert workers (priority topic) |
| `notifications` | `user_id` | 256 | 3 days | Many | Notification aggregator |
| `push-outbound` | `device_token` hash | 256 | 1 day | Alert workers | FCM/APNs senders |
| `impressions` | `tweet_id` | 512 | 1 day | Edge, clients | Analytics, view counters |
| `moderation-signals` | `user_id` | 64 | 7 days | Services | Spam and abuse jobs |
| `*.dlq` | original key | 16 | 14 days | Any consumer | Operators, replay tools |

Example payload for the central event (Protobuf-style):

```protobuf
message TweetCreated {
  int64  tweet_id       = 1;   // Snowflake, doubles as the event ID for idempotency
  int64  author_id      = 2;
  TweetKind kind        = 3;
  int64  in_reply_to_id = 4;
  int64  conversation_id = 5;
  int64  quoted_id      = 6;
  int64  retweet_of_id  = 7;
  repeated string hashtags = 8;
  repeated int64  mentioned_ids = 9;
  bool   has_media      = 10;
  Visibility visibility = 11;
  string lang           = 12;
  google.protobuf.Timestamp created_at = 13;
  bool   author_is_celebrity = 14;   // lets consumers skip push fan-out without a lookup
}
```

### A.8 Object storage layout

| Path pattern | Content |
| --- | --- |
| `media/orig/{yyyy}/{MM}/{mediaId}` | Uploaded original, kept for reprocessing |
| `media/img/{mediaId}/{w}x{h}.webp` | Resized image variants for the CDN |
| `media/vid/{mediaId}/master.m3u8` + segments | Adaptive-bitrate HLS renditions |
| `media/thumb/{mediaId}.jpg` | Video and link thumbnails |
| `avatars/{userId}/{hash}.webp` | Profile images, content-hash names for permanent caching |
| `exports/{userId}/{jobId}.zip` | Data-export archives with a short lifecycle rule |

Objects are immutable and addressed by ID or content hash, so CDN entries never need invalidation.

### A.9 Relationships at a glance

&#91;embedded content: core entities and relationships · 6 entities\]

A retweet, quote or reply is a tweet that points at another tweet; a like or bookmark is keyed by tweet and user. Notifications, alert subscriptions, device tokens, lists and conversations hang off a user by `user_id`.

### A.10 Which store owns what

| Store | Owns | Consistency | Recovery source |
| --- | --- | --- | --- |
| PostgreSQL | Accounts, credentials, settings, blocks, mutes, lists, media metadata, reports, audit | Strong, transactional per shard | Backups with point-in-time recovery, cross-region replicas |
| Cassandra | Tweets, timelines (durable), graph, likes, bookmarks, notifications, messages, outbox | `LOCAL_QUORUM` in-region, eventual across regions | Multi-region replicas |
| Redis | Hot timelines, tweet cache, counters, rate limits, sessions, trends | Best effort, may be lost | Rebuilt from Cassandra and Kafka |
| Elasticsearch | Search indices for tweets, users, hashtags | Near real time (seconds) | Reindex by replaying Kafka |
| Kafka | Domain events and the retry and dead-letter streams | Ordered per key, at-least-once | Cross-region mirroring, 7-day replay |
| Object storage and CDN | Media bytes and exports | Read-after-write per object, immutable keys | Cross-region replication |

**Index and key rules that make it work:** every table is read by its partition key; secondary indexes are avoided in Cassandra (a second query table is written instead); IDs are Snowflake so sort order equals time order; every consumer is idempotent on `tweet_id` or `event_id`; and any value that is derived (timeline, counter, search document, cache) can be dropped and rebuilt from the source tables.
