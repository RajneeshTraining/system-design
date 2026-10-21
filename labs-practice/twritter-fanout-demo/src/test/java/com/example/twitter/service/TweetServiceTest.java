package com.example.twitter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.twitter.TestWiring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TweetServiceTest {

    private TestWiring w;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        w = TestWiring.create();
        alice = w.newUser("alice");
        bob = w.newUser("bob");
    }

    @Test
    void createAndGetRoundTrip() {
        var created = w.tweets.create(alice, "  hello world  ", null, null);
        var fetched = w.tweets.get(created.id());
        assertEquals("hello world", fetched.text());
        assertEquals("alice", fetched.authorHandle());
        assertEquals(alice, fetched.authorId());
        assertEquals(0, fetched.likes());
    }

    @Test
    void unknownAuthorIsNotFound() {
        var ex = assertThrows(ApiException.class, () -> w.tweets.create(123L, "x", null, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    @Test
    void replyToMissingTweetIsNotFound() {
        var ex = assertThrows(ApiException.class, () -> w.tweets.create(alice, "reply", 42L, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    @Test
    void replyKeepsReference() {
        var parent = w.tweets.create(alice, "parent", null, null);
        var reply = w.tweets.create(bob, "reply", parent.id(), null);
        assertEquals(parent.id(), reply.inReplyToId());
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalTweet() {
        var first = w.tweets.create(alice, "once", null, "key-1");
        var second = w.tweets.create(alice, "once", null, "key-1");
        assertEquals(first.id(), second.id());
    }

    @Test
    void idempotencyKeysAreScopedPerUser() {
        var a = w.tweets.create(alice, "hi", null, "shared-key");
        var b = w.tweets.create(bob, "hi", null, "shared-key");
        assertNotEquals(a.id(), b.id());
    }

    @Test
    void onlyTheAuthorCanDelete() {
        var t = w.tweets.create(alice, "mine", null, null);
        var ex = assertThrows(ApiException.class, () -> w.tweets.delete(t.id(), bob));
        assertEquals(HttpStatus.FORBIDDEN, ex.status());
    }

    @Test
    void deleteIsSoftAndHidesTheTweet() {
        var t = w.tweets.create(alice, "bye", null, null);
        w.tweets.delete(t.id(), alice);
        var ex = assertThrows(ApiException.class, () -> w.tweets.get(t.id()));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        var again = assertThrows(ApiException.class, () -> w.tweets.delete(t.id(), alice));
        assertEquals(HttpStatus.NOT_FOUND, again.status());
    }

    @Test
    void likesAreIdempotentPerUser() {
        var t = w.tweets.create(alice, "like me", null, null);
        w.tweets.like(t.id(), bob);
        w.tweets.like(t.id(), bob);
        w.tweets.like(t.id(), alice);
        assertEquals(2, w.tweets.get(t.id()).likes());
    }

    @Test
    void likingAMissingTweetIsNotFound() {
        var ex = assertThrows(ApiException.class, () -> w.tweets.like(555L, alice));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }
}
