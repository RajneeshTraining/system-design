package com.example.twitter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.twitter.TestWiring;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FanoutServiceTest {

    private TestWiring w;

    @BeforeEach
    void setUp() {
        w = TestWiring.create();   // celebrity threshold = 3, synchronous fan-out
    }

    @Test
    void normalAuthorTweetIsPushedToEveryFollowerAndTheAuthor() {
        long alice = w.newUser("alice");
        long f1 = w.newUser("f1"), f2 = w.newUser("f2");
        w.followAll(alice, f1, f2);

        long id = w.tweets.create(alice, "hello", null, null).id();

        assertEquals(List.of(id), w.cache.read(f1, null, 10));
        assertEquals(List.of(id), w.cache.read(f2, null, 10));
        assertEquals(List.of(id), w.cache.read(alice, null, 10));
    }

    @Test
    void celebrityTweetIsNotPushedToFollowers() {
        long star = w.newUser("star");
        long f1 = w.newUser("f1"), f2 = w.newUser("f2"), f3 = w.newUser("f3");
        w.followAll(star, f1, f2, f3);

        long id = w.tweets.create(star, "hello", null, null).id();

        assertTrue(w.cache.read(f1, null, 10).isEmpty());
        assertTrue(w.cache.read(f2, null, 10).isEmpty());
        assertTrue(w.cache.read(f3, null, 10).isEmpty());
        assertEquals(List.of(id), w.cache.read(star, null, 10));   // the author still sees it
    }

    @Test
    void followerWhoJoinsLaterDoesNotReceiveOldPushedTweets() {
        long alice = w.newUser("alice");
        long early = w.newUser("early"), late = w.newUser("late");
        w.followAll(alice, early);
        w.tweets.create(alice, "before", null, null);
        w.followAll(alice, late);
        assertTrue(w.cache.read(late, null, 10).isEmpty());
    }
}
