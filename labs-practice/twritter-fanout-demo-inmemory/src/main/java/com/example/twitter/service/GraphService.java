package com.example.twitter.service;

import com.example.twitter.config.TwitterProperties;
import com.example.twitter.repo.GraphRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Follow graph plus the celebrity classification that drives the hybrid fan-out. */
@Service
public class GraphService {

    private final GraphRepository graph;
    private final UserService users;
    private final int celebrityThreshold;

    public GraphService(GraphRepository graph, UserService users, TwitterProperties props) {
        this.graph = graph;
        this.users = users;
        this.celebrityThreshold = props.celebrityThreshold();
    }

    public void follow(long followerId, long followeeId) {
        validate(followerId, followeeId);
        graph.follow(followerId, followeeId);      // idempotent
    }

    public void unfollow(long followerId, long followeeId) {
        validate(followerId, followeeId);
        graph.unfollow(followerId, followeeId);    // idempotent
    }

    /**
     * Celebrity = follower count at or above the threshold. The production design adds hysteresis
     * (promote at 100k, demote at 80k) so accounts near the line do not flap.
     */
    public boolean isCelebrity(long userId) {
        return graph.followerCount(userId) >= celebrityThreshold;
    }

    public List<Long> followersOf(long userId) {
        return graph.followersOf(userId);
    }

    public List<Long> followedCelebrities(long userId) {
        return graph.followingOf(userId).stream().filter(this::isCelebrity).toList();
    }

    public int followerCount(long userId) {
        return graph.followerCount(userId);
    }

    public int followingCount(long userId) {
        return graph.followingCount(userId);
    }

    private void validate(long followerId, long followeeId) {
        if (followerId == followeeId) {
            throw ApiException.badRequest("a user cannot follow themselves");
        }
        users.require(followerId);
        users.require(followeeId);
    }
}
