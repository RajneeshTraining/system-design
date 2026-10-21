package com.example.twitter.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

/**
 * Hydrated tweet returned by the API. Snowflake IDs are written as JSON strings
 * because 64-bit numbers lose precision in JavaScript clients.
 */
public record TweetView(
        @JsonSerialize(using = ToStringSerializer.class) long id,
        @JsonSerialize(using = ToStringSerializer.class) long authorId,
        String authorHandle,
        String text,
        @JsonSerialize(using = ToStringSerializer.class) Long inReplyToId,
        Instant createdAt,
        int likes) {
}
