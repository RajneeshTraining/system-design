package com.example.twitter.repo;

import com.example.twitter.model.Tweet;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * In-memory stand-in for the Cassandra {@code tweets}, {@code user_tweets} and {@code tweet_likes}
 * tables plus the Redis idempotency keys.
 */
@Repository
public class TweetRepository {

    private final Map<Long, Tweet> tweets = new ConcurrentHashMap<>();
    private final Map<Long, NavigableSet<Long>> byAuthor = new ConcurrentHashMap<>();   // newest first
    private final Map<Long, Set<Long>> likes = new ConcurrentHashMap<>();
    private final Map<String, Long> idempotency = new ConcurrentHashMap<>();

    public void save(Tweet tweet) {
        tweets.put(tweet.id(), tweet);
        byAuthor.computeIfAbsent(tweet.authorId(), k -> new ConcurrentSkipListSet<>(Comparator.reverseOrder()))
                .add(tweet.id());
    }

    public Optional<Tweet> find(long id) {
        return Optional.ofNullable(tweets.get(id));
    }

    /** Soft delete. */
    public void markDeleted(long id) {
        tweets.computeIfPresent(id, (k, t) -> t.markDeleted());
    }

    /** Latest non-deleted tweet IDs of an author, newest first, strictly older than {@code maxId} when given. */
    public List<Long> recentByAuthor(long authorId, Long maxId, int limit) {
        NavigableSet<Long> ids = byAuthor.get(authorId);
        if (ids == null) {
            return List.of();
        }
        NavigableSet<Long> window = (maxId == null) ? ids : ids.tailSet(maxId, false);   // reverse order: tail = older
        List<Long> out = new ArrayList<>(limit);
        for (Long id : window) {
            if (out.size() >= limit) {
                break;
            }
            Tweet t = tweets.get(id);
            if (t != null && !t.deleted()) {
                out.add(id);
            }
        }
        return out;
    }

    /** @return true if this is a new like (liking twice is a no-op). */
    public boolean like(long tweetId, long userId) {
        return likes.computeIfAbsent(tweetId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    public int likeCount(long tweetId) {
        return likes.getOrDefault(tweetId, Set.of()).size();
    }

    /** @return the tweet id already registered for this key, or null if this call registered it. */
    public Long registerIdempotencyKey(String key, long tweetId) {
        return idempotency.putIfAbsent(key, tweetId);
    }
}
