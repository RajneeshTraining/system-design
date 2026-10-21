package com.example.twitter.repo;

import com.example.twitter.config.TwitterProperties;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * In-memory stand-in for the Redis home-timeline lists ({@code LPUSH} + {@code LTRIM 800}).
 * Holds tweet IDs only; bodies are hydrated from {@link TweetRepository}.
 */
@Repository
public class TimelineCache {

    private final Map<Long, NavigableSet<Long>> timelines = new ConcurrentHashMap<>();   // newest first
    private final int max;

    public TimelineCache(TwitterProperties props) {
        this.max = props.timelineMax();
    }

    public void push(long userId, long tweetId) {
        NavigableSet<Long> tl = timelines.computeIfAbsent(userId, k -> new ConcurrentSkipListSet<>(Comparator.reverseOrder()));
        tl.add(tweetId);
        while (tl.size() > max) {
            tl.pollLast();
        }
    }

    /** Tweet IDs for a user, newest first, strictly older than {@code maxId} when given. */
    public List<Long> read(long userId, Long maxId, int limit) {
        NavigableSet<Long> tl = timelines.get(userId);
        if (tl == null) {
            return List.of();
        }
        NavigableSet<Long> window = (maxId == null) ? tl : tl.tailSet(maxId, false);
        List<Long> out = new ArrayList<>(limit);
        for (Long id : window) {
            if (out.size() >= limit) {
                break;
            }
            out.add(id);
        }
        return out;
    }
}
