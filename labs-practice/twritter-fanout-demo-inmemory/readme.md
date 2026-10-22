# twitter-fanout-demo

A minimal **Java 21 + Spring Boot 3** service that demonstrates the core idea behind a Twitter/X-scale timeline: **hybrid fan-out**.

- **Normal authors:** when they tweet, the tweet ID is *pushed* into every follower's home timeline (fan-out on write).
- **Celebrities:** nothing is pushed. Followers *pull* the celebrity's latest tweets when they open their timeline, and the two lists are merged (fan-out on read).

This avoids writing one tweet into millions of timelines while keeping reads fast for everyone else. This repository is the first, deliberately small slice of a larger enterprise design; everything runs in memory so you can start it with **no database, Kafka or Docker**.

---

## What is (and is not) in this version

| Included | Not included yet (planned) |
| --- | --- |
| Users, follow / unfollow | Unit and integration tests (JUnit) |
| Post tweet (280 chars), reply reference, soft delete, like | Redis, Cassandra, PostgreSQL, Kafka (in-memory stand-ins are used) |
| Hybrid fan-out: push for normal authors, pull for celebrities | Authentication (the acting user is passed explicitly) |
| Home timeline and profile timeline, cursor paging | Search, trends, media, notifications, DMs |
| Snowflake 64-bit IDs (time-sortable) | Rate limiting, circuit breakers, metrics dashboards |
| Idempotent tweet creation (`Idempotency-Key` header) | Dockerfile, Kubernetes manifests, CI pipeline |
| Validation and RFC 9457 `problem+json` errors | |
| Java 21 virtual threads, Actuator health endpoint | |

> **Data resets on every restart.** All state lives in memory.

---

## Prerequisites

| Tool | Version | Check with |
| --- | --- | --- |
| JDK | **21** or newer | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| git | any recent | `git --version` |
| curl | any (only for the demo script and manual tries) | `curl --version` |

This project does not ship the Maven wrapper. Either install Maven, or run `mvn -N wrapper:wrapper` once to generate `mvnw`.

---

## Quick start

```bash
# 1. Extract the archive (it already contains the git history)
unzip twitter-fanout-demo.zip
cd twitter-fanout-demo

# 2. Confirm the repository and the first commit
git log --oneline

# 3. Build
mvn clean package

# 4. Run (option A: Maven)
mvn spring-boot:run

#    or (option B: the packaged jar)
java -jar target/twitter-fanout-demo-0.1.0.jar
```

Check that it is up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

Stop it with `Ctrl+C`. To use another port: `java -jar target/twitter-fanout-demo-0.1.0.jar --server.port=9090`.

---

## Try it

### Automated walkthrough

With the app running in one terminal, in another:

```bash
./scripts/demo.sh
```

On Windows, run it from Git Bash or WSL. It creates a celebrity and a normal author, makes three fans follow them, posts a tweet from each, and shows that:

1. A fan who follows both sees **both** tweets, newest first.
2. A fan who follows only the celebrity sees just the celebrity tweet.
3. Re-sending a create request with the same `Idempotency-Key` returns the same tweet.

In the server log you will see `skipping push fan-out` for the celebrity and `pushed to N follower timelines` for the normal author.

### Manual walkthrough

IDs are 64-bit and returned as **JSON strings**. Replace the placeholders with the IDs you get back.

```bash
# Create users
curl -X POST localhost:8080/api/v1/users -H 'Content-Type: application/json' -d '{"handle":"star"}'
curl -X POST localhost:8080/api/v1/users -H 'Content-Type: application/json' -d '{"handle":"fan1"}'

# fan1 follows star
curl -X POST localhost:8080/api/v1/users/<FAN1_ID>/follow/<STAR_ID>

# star tweets
curl -X POST localhost:8080/api/v1/tweets -H 'Content-Type: application/json' \
     -d '{"authorId":"<STAR_ID>","text":"Hello world"}'

# fan1 reads the home timeline
curl "localhost:8080/api/v1/users/<FAN1_ID>/timeline/home?limit=20"

# next page: pass the nextCursor from the previous response as maxId
curl "localhost:8080/api/v1/users/<FAN1_ID>/timeline/home?limit=20&maxId=<NEXT_CURSOR>"
```

> With the default threshold of **3 followers**, an account becomes a "celebrity" as soon as it has 3 followers. Follow `star` from three different users to see the pull path.

---

## API reference

Base path: `/api/v1`. There is no authentication in this version: the acting user is given explicitly (`authorId` in the body, `userId` as a query parameter).

| Method | Path | Body / params | Success | Notes |
| --- | --- | --- | --- | --- |
| POST | `/users` | `{"handle","displayName?"}` | `201` user | Handle: 1-15 letters, digits or `_`; unique, case-insensitive (`409` if taken) |
| GET | `/users/{id}` | | `200` user | Includes `followers`, `following`, `celebrity` |
| POST | `/users/{id}/follow/{targetId}` | | `204` | Idempotent; `400` if following yourself |
| DELETE | `/users/{id}/follow/{targetId}` | | `204` | Idempotent |
| POST | `/tweets` | `{"authorId","text","inReplyToId?"}`; header `Idempotency-Key?` | `201` tweet | Text max 280 chars |
| GET | `/tweets/{id}` | | `200` tweet | `404` if missing or deleted |
| DELETE | `/tweets/{id}?userId=` | | `204` | Author only (`403` otherwise); soft delete |
| POST | `/tweets/{id}/likes?userId=` | | `204` | Idempotent per user |
| GET | `/users/{id}/timeline/home` | `limit?` `maxId?` | `200` page | Pushed + pulled tweets, newest first |
| GET | `/users/{id}/tweets` | `limit?` `maxId?` | `200` page | The user's own tweets |
| GET | `/actuator/health` | | `200` | Liveness check |

A page looks like `{"items":[...],"nextCursor":"<id or null>"}`. Pass `nextCursor` back as `maxId` to get the next (older) page; `null` means there are no more.

Errors use `application/problem+json`, for example:

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"user 123 not found","instance":"/api/v1/users/123/timeline/home"}
```

---

## Configuration

Set in `src/main/resources/application.yml`, or override on the command line (for example `--twitter.celebrity-threshold=100000`).

| Property | Default | Meaning |
| --- | --- | --- |
| `twitter.celebrity-threshold` | `3` | Followers needed to be treated as a celebrity. **Demo value; use about 100000 in production.** |
| `twitter.timeline-max` | `800` | Tweet IDs kept per home timeline |
| `twitter.default-page-size` | `20` | Page size when `limit` is omitted |
| `twitter.max-page-size` | `100` | Upper bound for `limit` |
| `twitter.worker-id` | `1` | Snowflake worker ID (0-1023); must be unique per running instance |
| `twitter.fanout-async` | `true` | `false` runs fan-out inside the request, which is handy for debugging |
| `server.port` | `8080` | HTTP port |

---

## How it works

```
POST /tweets
   |
   v
TweetService  --save-->  TweetRepository
   |
   v
FanoutService
   |-- author is a celebrity?  yes -> push nothing (followers pull later)
   '-- no -> push tweet ID into every follower's TimelineCache (virtual-thread executor)

GET /users/{id}/timeline/home
   |
   v
TimelineService
   |-- pushed IDs      <- TimelineCache (normal authors)
   |-- pulled IDs      <- TweetRepository.recentByAuthor() for each celebrity the user follows
   '-- merge (distinct, newest first by Snowflake id) -> hydrate -> drop deleted -> page + cursor
```

### How the in-memory pieces map to the production design

| This repo | Production equivalent |
| --- | --- |
| `UserRepository` | PostgreSQL accounts (sharded) |
| `TweetRepository` | Cassandra `tweets`, `user_tweets`, `tweet_likes`; Redis idempotency keys |
| `GraphRepository` | Cassandra `followers` / `following` (sharded for celebrities) |
| `TimelineCache` | Redis lists (`LPUSH` + `LTRIM 800`), with a Cassandra durable copy |
| `FanoutService` (executor) | Kafka `tweet-created` topic with a consumer group |
| `GraphService.isCelebrity` | Celebrity flag with hysteresis (promote at 100k, demote at 80k) |

Simplifications to be aware of: fan-out pushes to **all** followers (production pushes only to recently active ones), the celebrity check has no hysteresis, and there is no persistence or authentication.

---

## Project structure

```
twitter-fanout-demo/
|-- pom.xml
|-- README.md
|-- scripts/demo.sh                      end-to-end walkthrough (bash + curl)
`-- src/main/
    |-- resources/application.yml
    `-- java/com/example/twitter/
        |-- TwitterApplication.java
        |-- config/TwitterProperties.java    typed twitter.* settings
        |-- model/                           User, Tweet, TweetView, TimelinePage (records)
        |-- repo/                            in-memory stores (UserRepository, GraphRepository,
        |                                    TweetRepository, TimelineCache)
        |-- service/                         SnowflakeIdGenerator, UserService, GraphService,
        |                                    TweetService, FanoutService, TimelineService, ApiException
        `-- api/                             REST controllers + ApiExceptionHandler
```

---

## Validate, then push to GitHub

1. **Validate locally:** `mvn clean package`, start the app, and run `./scripts/demo.sh`. Fix anything you want to change and commit it.
2. **Check the commit author** (the first commit is already made):
   ```bash
   git log -1 --format='%an <%ae>'
   # If you want a different identity:
   git config user.name  "Your Name"
   git config user.email "you@example.com"
   git commit --amend --reset-author --no-edit
   ```
3. **Create an empty repository on GitHub** (no README, no .gitignore, no license), then:
   ```bash
   git branch -M main
   git remote add origin https://github.com/<your-user>/twitter-fanout-demo.git
   git push -u origin main
   ```
   Or with the GitHub CLI, from the project folder:
   ```bash
   gh repo create twitter-fanout-demo --private --source=. --remote=origin --push
   ```
4. Add a `LICENSE` file of your choice before making the repository public.

---

## Roadmap

1. Unit tests (JUnit 5, Mockito) and integration tests (Spring Boot Test, Testcontainers).
2. Swap the in-memory stores for Redis (timelines, cache) and Cassandra (tweets, graph) behind the same repository classes.
3. Replace the executor with Kafka and add the transactional outbox.
4. JWT authentication, rate limiting (token bucket), Resilience4j circuit breakers.
5. Metrics and tracing (Micrometer, OpenTelemetry), Dockerfile, Kubernetes manifests, CI.
6. Notifications, search, trends and media.

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `invalid target release: 21` or `release version 21 not supported` | Run on JDK 21+. Check `java -version` and `JAVA_HOME`. |
| `Port 8080 was already in use` | Start with `--server.port=9090`, or stop the other process. |
| `mvn: command not found` | Install Maven 3.9+, or generate the wrapper on a machine that has it. |
| `./scripts/demo.sh: Permission denied` | `chmod +x scripts/demo.sh`, or run `bash scripts/demo.sh`. |
| Demo script fails to parse IDs | Make sure the server is running and `BASE_URL` points to it. |
| Timeline looks empty right after posting | Fan-out is asynchronous. Retry after a moment, or run with `--twitter.fanout-async=false`. |
| Data disappeared | Expected: storage is in memory and clears on restart. |
