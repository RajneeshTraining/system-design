package com.example.twitter.model;

import java.time.Instant;

/** Stored tweet. Deletes are soft: the row stays, readers filter it out. */
public record Tweet(long id, long authorId, String text, Long inReplyToId, Instant createdAt, boolean deleted) {

    public Tweet markDeleted() {
        return new Tweet(id, authorId, text, inReplyToId, createdAt, true);
    }
}
