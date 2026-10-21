package com.example.twitter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.twitter.TestWiring;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GraphServiceTest {

    private TestWiring w;

    @BeforeEach
    void setUp() {
        w = TestWiring.create();   // celebrity threshold = 3
    }

    @Test
    void becomesCelebrityExactlyAtTheThreshold() {
        long star = w.newUser("star");
        long f1 = w.newUser("f1"), f2 = w.newUser("f2"), f3 = w.newUser("f3");
        w.followAll(star, f1, f2);
        assertFalse(w.graph.isCelebrity(star));
        w.followAll(star, f3);
        assertTrue(w.graph.isCelebrity(star));
    }

    @Test
    void followingTwiceDoesNotInflateTheCount() {
        long a = w.newUser("a"), b = w.newUser("b");
        w.graph.follow(a, b);
        w.graph.follow(a, b);
        assertEquals(1, w.graph.followerCount(b));
    }

    @Test
    void followedCelebritiesListsOnlyCelebrities() {
        long star = w.newUser("star"), normal = w.newUser("normal"), fan = w.newUser("fan");
        long f2 = w.newUser("f2"), f3 = w.newUser("f3");
        w.followAll(star, fan, f2, f3);
        w.followAll(normal, fan);
        assertEquals(List.of(star), w.graph.followedCelebrities(fan));
    }

    @Test
    void cannotFollowYourself() {
        long a = w.newUser("a");
        var ex = assertThrows(ApiException.class, () -> w.graph.follow(a, a));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void unknownUsersAreRejected() {
        long a = w.newUser("a");
        var ex = assertThrows(ApiException.class, () -> w.graph.follow(a, 999_999L));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    @Test
    void unfollowRestoresNonCelebrityStatus() {
        long star = w.newUser("star");
        long f1 = w.newUser("f1"), f2 = w.newUser("f2"), f3 = w.newUser("f3");
        w.followAll(star, f1, f2, f3);
        assertTrue(w.graph.isCelebrity(star));
        w.graph.unfollow(f3, star);
        assertFalse(w.graph.isCelebrity(star));
    }
}
