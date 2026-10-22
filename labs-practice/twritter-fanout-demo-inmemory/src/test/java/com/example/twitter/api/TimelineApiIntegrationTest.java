package com.example.twitter.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack tests: real Spring context, real controllers, in-memory stores, no network.
 * Relies on the default {@code twitter.celebrity-threshold=3} from application.yml.
 */
@SpringBootTest(properties = "twitter.fanout-async=false")
@AutoConfigureMockMvc
class TimelineApiIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();   // keeps handles unique in the shared context

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    // ---------- helpers ----------

    private String idOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createUser(String prefix) throws Exception {
        String body = json.writeValueAsString(Map.of("handle", prefix + SEQ.incrementAndGet()));
        return idOf(mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private String createTweet(String authorId, String text) throws Exception {
        String body = json.writeValueAsString(Map.of("authorId", authorId, "text", text));
        return idOf(mvc.perform(post("/api/v1/tweets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private void follow(String followerId, String followeeId) throws Exception {
        mvc.perform(post("/api/v1/users/{id}/follow/{target}", followerId, followeeId))
                .andExpect(status().isNoContent());
    }

    // ---------- users ----------

    @Test
    void createUserReturns201WithLocationAndStringId() throws Exception {
        String body = json.writeValueAsString(Map.of("handle", "newbie" + SEQ.incrementAndGet()));
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.followers").value(0))
                .andExpect(jsonPath("$.celebrity").value(false));
    }

    @Test
    void duplicateHandleReturns409() throws Exception {
        String handle = "dupe" + SEQ.incrementAndGet();
        String body = json.writeValueAsString(Map.of("handle", handle));
        String sameHandleOtherCase = json.writeValueAsString(Map.of("handle", handle.toUpperCase()));
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(sameHandleOtherCase))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already taken")));
    }

    @Test
    void invalidHandleReturns400() throws Exception {
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"bad handle!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("handle")));
    }

    @Test
    void unknownUserTimelineReturns404() throws Exception {
        mvc.perform(get("/api/v1/users/{id}/timeline/home", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("not found")));
    }

    // ---------- tweets and timelines ----------

    @Test
    void tweetLongerThan280CharsReturns400() throws Exception {
        String author = createUser("longtxt");
        String body = json.writeValueAsString(Map.of("authorId", author, "text", "x".repeat(281)));
        mvc.perform(post("/api/v1/tweets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("text")));
    }

    @Test
    void tweetOfFollowedUserAppearsInHomeTimeline() throws Exception {
        String author = createUser("author");
        String reader = createUser("reader");
        follow(reader, author);
        String tweetId = createTweet(author, "hello normal world");

        mvc.perform(get("/api/v1/users/{id}/timeline/home", reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].text").value("hello normal world"))
                .andExpect(jsonPath("$.items[0].id").value(tweetId))
                .andExpect(jsonPath("$.items[0].id").isString());
    }

    @Test
    void celebrityTweetIsPulledIntoFollowersTimeline() throws Exception {
        String star = createUser("star");
        String fan1 = createUser("fan");
        String fan2 = createUser("fan");
        String fan3 = createUser("fan");
        follow(fan1, star);
        follow(fan2, star);
        follow(fan3, star);   // 3 followers = celebrity at the default threshold

        mvc.perform(get("/api/v1/users/{id}", star))
                .andExpect(jsonPath("$.celebrity").value(true));

        String tweetId = createTweet(star, "hello from the celebrity");
        mvc.perform(get("/api/v1/users/{id}/timeline/home", fan2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(tweetId))
                .andExpect(jsonPath("$.items[0].authorHandle").value(containsString("star")));
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameTweet() throws Exception {
        String author = createUser("idem");
        String body = json.writeValueAsString(Map.of("authorId", author, "text", "send once"));
        String first = idOf(mvc.perform(post("/api/v1/tweets").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn());
        String second = idOf(mvc.perform(post("/api/v1/tweets").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn());
        assertEquals(first, second);
    }

    @Test
    void deleteIsRestrictedToTheAuthorAndHidesTheTweet() throws Exception {
        String author = createUser("owner");
        String other = createUser("other");
        String tweetId = createTweet(author, "delete me");

        mvc.perform(delete("/api/v1/tweets/{id}", tweetId).param("userId", other))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/tweets/{id}", tweetId).param("userId", author))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tweets/{id}", tweetId))
                .andExpect(status().isNotFound());
    }
}
