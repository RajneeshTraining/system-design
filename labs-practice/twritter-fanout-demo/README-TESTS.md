# Tests add-on for twitter-fanout-demo

This archive adds the JUnit 5 tests that were left out of the first version. Nothing under `src/main` changes.

## What is in this archive

```
twitter-fanout-demo-tests/
|-- README-TESTS.md                  this file
|-- apply-tests.sh                   copies everything into your project
|-- pom.xml                          your pom.xml + one test dependency (spring-boot-starter-test)
`-- src/test/java/com/example/twitter/
    |-- TestWiring.java                              shared helper: builds the real objects without Spring
    |-- repo/
    |   |-- GraphRepositoryTest.java
    |   `-- TimelineCacheTest.java
    |-- service/
    |   |-- SnowflakeIdGeneratorTest.java
    |   |-- GraphServiceTest.java
    |   |-- TweetServiceTest.java
    |   |-- FanoutServiceTest.java
    |   `-- TimelineServiceTest.java
    `-- api/
        `-- TimelineApiIntegrationTest.java          full-stack MockMvc tests
```

## Where each file goes

Everything goes **inside your existing project folder** (`twitter-fanout-demo/`, the one that contains `pom.xml`). The folder structure in this archive mirrors the project, so the package of each file is already its folder.

| File in this archive | Copy to (inside `twitter-fanout-demo/`) | Java package |
| --- | --- | --- |
| `pom.xml` | `pom.xml` (replace; see the note below) | n/a |
| `src/test/java/com/example/twitter/TestWiring.java` | `src/test/java/com/example/twitter/` | `com.example.twitter` |
| `src/test/java/com/example/twitter/repo/*Test.java` | `src/test/java/com/example/twitter/repo/` | `com.example.twitter.repo` |
| `src/test/java/com/example/twitter/service/*Test.java` | `src/test/java/com/example/twitter/service/` | `com.example.twitter.service` |
| `src/test/java/com/example/twitter/api/TimelineApiIntegrationTest.java` | `src/test/java/com/example/twitter/api/` | `com.example.twitter.api` |

`src/test/java` does not exist yet in your project, so create it (the script and the commands below do that for you). Keep each test in the package that matches its folder (the `package` line at the top of each file already does). The integration test must stay under `com.example.twitter` so Spring Boot can find `TwitterApplication` in a parent package.

**About `pom.xml`:** the only change is this dependency added inside `<dependencies>`. If you have customised your pom, add just this block by hand instead of replacing the file:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

## Install (pick one)

### Option A: script (macOS, Linux, Git Bash, WSL)

```bash
unzip twitter-fanout-demo-tests.zip
cd twitter-fanout-demo-tests
./apply-tests.sh /path/to/twitter-fanout-demo
```

If the pom was replaced, the script keeps your old one as `pom.xml.bak`. Delete it afterwards and do not commit it.

### Option B: manual copy (any OS)

```bash
unzip twitter-fanout-demo-tests.zip
cp -R twitter-fanout-demo-tests/src/test  /path/to/twitter-fanout-demo/src/
cp    twitter-fanout-demo-tests/pom.xml   /path/to/twitter-fanout-demo/pom.xml
```

On Windows File Explorer: copy the `src` folder from the archive into your project folder and choose "merge" when asked, then replace `pom.xml`.

## Run

From the project folder (where `pom.xml` is):

```bash
mvn clean test                                   # everything: 46 unit tests + 9 integration tests
mvn -Dtest=TimelineServiceTest test              # one class
mvn -Dtest=TimelineServiceTest#cursorPagingWalksEverythingWithoutOverlap test   # one method
mvn -Dtest='*ServiceTest' test                   # by pattern
mvn clean verify                                 # build + tests + jar
```

Expected result: `Tests run: 55, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. Reports are written to `target/surefire-reports/`.

In IntelliJ IDEA or VS Code: right-click `src/test/java` and choose Run Tests (reload the Maven project first so the new dependency is picked up).

No Docker, database or network is needed. The integration test starts the app inside the test JVM with in-memory stores.

## What the tests cover

| Class | Type | Checks |
| --- | --- | --- |
| `SnowflakeIdGeneratorTest` | unit | strictly increasing ids, uniqueness across 32 virtual threads, worker id bits, invalid worker id |
| `TimelineCacheTest` | unit | newest first, trimmed to the max size, exclusive `maxId` cursor, limit, duplicates ignored |
| `GraphRepositoryTest` | unit | idempotent follow, edges stored both ways, unfollow, regression test for unfollowing a missing edge |
| `GraphServiceTest` | unit | celebrity threshold boundary, self-follow rejected, unknown user, unfollow demotes a celebrity |
| `TweetServiceTest` | unit | create/get, replies, idempotency keys (and per-user scope), author-only soft delete, idempotent likes |
| `FanoutServiceTest` | unit | normal author is pushed to followers and self, celebrity is not pushed, late followers get no backfill |
| `TimelineServiceTest` | unit | push + pull merge, cursor paging across pages, deleted tweets hidden, profile timeline, page-size clamping, de-duplication when an author becomes a celebrity |
| `TimelineApiIntegrationTest` | integration | HTTP status codes, `problem+json` bodies, ids as JSON strings, `Location` header, celebrity pull end to end, idempotency header, delete rules |

## Commit the tests

```bash
git add src/test pom.xml
git status                  # make sure pom.xml.bak is not staged
git commit -m "Add JUnit 5 unit and MockMvc integration tests"
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `package org.junit.jupiter.api does not exist` | The new pom was not applied. Check that `spring-boot-starter-test` is in `pom.xml`, then re-run Maven (or reload the Maven project in your IDE). |
| `No tests found` or `Tests run: 0` | Tests must be under `src/test/java` and end with `Test`. Check the folder was copied to the right place. |
| `Unable to find a @SpringBootConfiguration` | `TimelineApiIntegrationTest.java` must be under `src/test/java/com/example/twitter/api/` so Spring can find `TwitterApplication` in the parent package. |
| Integration test fails on the celebrity case | It relies on `twitter.celebrity-threshold: 3` in `application.yml`. If you changed that value, change it back or adjust the test. |
| `invalid target release: 21` | Run Maven on JDK 21+ (`java -version`, `JAVA_HOME`). |

## Verification status (please read)

The sandbox these files were prepared in cannot reach Maven Central, so the full Maven run could not be done there. What was verified: the 46 unit tests were compiled and executed against the real production classes using a stand-in for the JUnit Jupiter API, and all passed. `TimelineApiIntegrationTest` needs Spring Boot on the classpath and has not been run. If any test fails on your machine, please send me the Maven output.

## Next steps

1. Mockito-based tests for failure paths (once the in-memory stores are replaced by Redis/Cassandra clients).
2. Testcontainers integration tests for Redis, Cassandra and Kafka.
3. A GitHub Actions workflow that runs `mvn clean verify` on every push.
