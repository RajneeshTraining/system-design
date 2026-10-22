package com.example.twitter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.twitter.TestWiring;
import com.example.twitter.model.TimelinePage;
import com.example.twitter.model.TweetView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimelineServiceTest {

    private TestWiring w;
    private long star;     // celebrity (3 followers)
    private long alice;    // normal author
    private long fan1;     // follows star and alice
    private long fan2;     // follows star only
    private long fan3;     // follows star only

    @BeforeEach
    void setUp() {
        w = TestWiring.create();
        star = w.newUser("star");
        alice = w.newUser("alice");
        fan1 = w.newUser("fan1");
        fan2 = w.newUser("fan2");
        fan3 = w.newUser("fan3");
        w.followAll(star, fan1, fan2, fan3);
        w.followAll(alice, fan1);
    }

    private static List<Long> ids(TimelinePage page) {
        return page.items().stream().map(TweetView::id).toList();
    }

    @Test
    void homeMergesPushedAndPulledTweetsNewestFirst() {
        long a = w.tweets.create(alice, "from alice (pushed)", null, null).id();
        long s = w.tweets.create(star, "from star (pulled)", null, null).id();
        assertEquals(List.of(s, a), ids(w.timelines.home(fan1, null, null)));
    }

    @Test
    void fanWhoOnlyFollowsTheCelebritySeesOnlyTheCelebrity() {
        w.tweets.create(alice, "alice", null, null);
        long s = w.tweets.create(star, "star", null, null).id();
        assertEquals(List.of(s), ids(w.timelines.home(fan2, null, null)));
    }

    @Test
    void authorSeesOwnTweetsInHome() {
        long a = w.tweets.create(alice, "mine", null, null).id();
        assertEquals(List.of(a), ids(w.timelines.home(alice, null, null)));
    }

    @Test
    void unfollowingTheCelebrityHidesTheirTweets() {
        w.tweets.create(star, "star", null, null);
        assertEquals(1, w.timelines.home(fan3, null, null).items().size());
        w.graph.unfollow(fan3, star);
        assertTrue(w.timelines.home(fan3, null, null).items().isEmpty());
    }

    @Test
    void cursorPagingWalksEverythingWithoutOverlap() {
        List<Long> posted = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            posted.add(w.tweets.create(star, "s" + i, null, null).id());
        }
        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            TimelinePage page = w.timelines.home(fan2, cursor, 3);
            seen.addAll(ids(page));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertEquals(3, pages);                                  // 3 + 3 + 1
        assertEquals(7, seen.size());
        assertEquals(7, new HashSet<>(seen).size());             // no duplicates
        List<Long> newestFirst = new ArrayList<>(posted);
        java.util.Collections.reverse(newestFirst);
        assertEquals(newestFirst, seen);                         // strictly newest first across pages
    }

    @Test
    void lastPageHasNoCursor() {
        w.tweets.create(star, "only", null, null);
        TimelinePage page = w.timelines.home(fan2, null, 5);
        assertNull(page.nextCursor());
    }

    @Test
    void fullPageReturnsACursor() {
        for (int i = 0; i < 3; i++) {
            w.tweets.create(star, "s" + i, null, null);
        }
        assertNotNull(w.timelines.home(fan2, null, 3).nextCursor());
    }

    @Test
    void deletedTweetsDisappearFromHome() {
        long keep = w.tweets.create(star, "keep", null, null).id();
        long gone = w.tweets.create(star, "gone", null, null).id();
        w.tweets.delete(gone, star);
        List<Long> got = ids(w.timelines.home(fan2, null, null));
        assertEquals(List.of(keep), got);
        assertFalse(got.contains(gone));
    }

    @Test
    void deletedPushedTweetsDisappearToo() {
        long gone = w.tweets.create(alice, "gone", null, null).id();
        w.tweets.delete(gone, alice);
        assertTrue(w.timelines.home(fan1, null, null).items().isEmpty());
    }

    @Test
    void profileTimelineShowsOnlyTheUsersOwnTweets() {
        long a = w.tweets.create(alice, "alice", null, null).id();
        w.tweets.create(star, "star", null, null);
        assertEquals(List.of(a), ids(w.timelines.profile(alice, null, null)));
    }

    @Test
    void pageSizeIsClamped() {
        for (int i = 0; i < 105; i++) {
            w.tweets.create(alice, "t" + i, null, null);
        }
        assertEquals(1, w.timelines.profile(alice, null, 0).items().size());          // min 1
        assertEquals(100, w.timelines.profile(alice, null, 1000).items().size());     // max 100
        assertEquals(20, w.timelines.profile(alice, null, null).items().size());      // default 20
    }

    @Test
    void unknownUserIsNotFound() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> w.timelines.home(424_242L, null, null));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.status());
    }

    @Test
    void feedHasNoDuplicatesWhenAuthorBecomesCelebrityAfterPosting() {
        // alice tweets while normal (pushed to fan1), then becomes a celebrity (pulled as well)
        long before = w.tweets.create(alice, "before", null, null).id();
        w.followAll(alice, fan2, fan3);                       // alice now has 3 followers
        assertTrue(w.graph.isCelebrity(alice));
        long after = w.tweets.create(alice, "after", null, null).id();

        List<Long> got = ids(w.timelines.home(fan1, null, null));
        Set<Long> unique = new HashSet<>(got);
        assertEquals(got.size(), unique.size());              // pushed + pulled copies are de-duplicated
        assertEquals(List.of(after, before), got);
    }
}
