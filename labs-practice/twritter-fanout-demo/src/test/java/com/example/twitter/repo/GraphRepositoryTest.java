package com.example.twitter.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphRepositoryTest {

    private final GraphRepository graph = new GraphRepository();

    @Test
    void followIsIdempotent() {
        assertTrue(graph.follow(1L, 2L));
        assertFalse(graph.follow(1L, 2L));
        assertEquals(1, graph.followerCount(2L));
        assertEquals(1, graph.followingCount(1L));
    }

    @Test
    void edgesAreStoredInBothDirections() {
        graph.follow(1L, 2L);
        assertEquals(List.of(2L), graph.followingOf(1L));
        assertEquals(List.of(1L), graph.followersOf(2L));
    }

    @Test
    void unfollowRemovesBothDirections() {
        graph.follow(1L, 2L);
        assertTrue(graph.unfollow(1L, 2L));
        assertEquals(0, graph.followerCount(2L));
        assertEquals(0, graph.followingCount(1L));
    }

    /** Regression test: unfollowing an edge that never existed must not throw. */
    @Test
    void unfollowOfMissingEdgeIsANoOp() {
        assertFalse(graph.unfollow(1L, 2L));
        graph.follow(1L, 3L);
        assertFalse(graph.unfollow(1L, 2L));
        assertEquals(1, graph.followingCount(1L));
    }

    @Test
    void returnedListsAreSnapshots() {
        graph.follow(1L, 2L);
        List<Long> snapshot = graph.followersOf(2L);
        graph.follow(3L, 2L);
        assertEquals(1, snapshot.size());
        assertEquals(2, graph.followerCount(2L));
    }
}
