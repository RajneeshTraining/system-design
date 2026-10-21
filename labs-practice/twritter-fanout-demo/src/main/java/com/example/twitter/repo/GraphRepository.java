package com.example.twitter.repo;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory stand-in for the Cassandra {@code followers} / {@code following} tables (stored both ways). */
@Repository
public class GraphRepository {

    private final Map<Long, Set<Long>> following = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> followers = new ConcurrentHashMap<>();

    /** @return true if the edge was newly created. */
    public boolean follow(long followerId, long followeeId) {
        boolean added = following.computeIfAbsent(followerId, k -> ConcurrentHashMap.newKeySet()).add(followeeId);
        if (added) {
            followers.computeIfAbsent(followeeId, k -> ConcurrentHashMap.newKeySet()).add(followerId);
        }
        return added;
    }

    /** @return true if the edge existed and was removed. */
    public boolean unfollow(long followerId, long followeeId) {
        Set<Long> out = following.get(followerId);
        boolean removed = out != null && out.remove(followeeId);
        if (removed) {
            Set<Long> in = followers.get(followeeId);
            if (in != null) {
                in.remove(followerId);
            }
        }
        return removed;
    }

    public List<Long> followingOf(long userId) {
        return List.copyOf(following.getOrDefault(userId, Set.of()));
    }

    public List<Long> followersOf(long userId) {
        return List.copyOf(followers.getOrDefault(userId, Set.of()));
    }

    public int followerCount(long userId) {
        return followers.getOrDefault(userId, Set.of()).size();
    }

    public int followingCount(long userId) {
        return following.getOrDefault(userId, Set.of()).size();
    }
}
