package com.example.twitter.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.twitter.config.TwitterProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineCacheTest {

    private static TimelineCache cache(int max) {
        return new TimelineCache(new TwitterProperties(3, max, 20, 100, 1, false));
    }

    @Test
    void returnsNewestFirstAndTrimsToMax() {
        var cache = cache(3);
        for (long id = 1; id <= 5; id++) {
            cache.push(7L, id);
        }
        assertEquals(List.of(5L, 4L, 3L), cache.read(7L, null, 10));
    }

    @Test
    void maxIdIsAnExclusiveCursor() {
        var cache = cache(800);
        for (long id = 1; id <= 5; id++) {
            cache.push(7L, id);
        }
        assertEquals(List.of(3L, 2L, 1L), cache.read(7L, 4L, 10));
    }

    @Test
    void respectsLimit() {
        var cache = cache(800);
        for (long id = 1; id <= 5; id++) {
            cache.push(7L, id);
        }
        assertEquals(List.of(5L, 4L), cache.read(7L, null, 2));
    }

    @Test
    void duplicatePushIsIgnored() {
        var cache = cache(800);
        cache.push(7L, 1L);
        cache.push(7L, 1L);
        assertEquals(List.of(1L), cache.read(7L, null, 10));
    }

    @Test
    void unknownUserHasEmptyTimeline() {
        assertTrue(cache(800).read(99L, null, 10).isEmpty());
    }
}
