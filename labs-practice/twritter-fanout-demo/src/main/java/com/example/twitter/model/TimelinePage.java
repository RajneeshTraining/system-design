package com.example.twitter.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/** One page of tweets plus a cursor. Pass {@code nextCursor} back as {@code maxId} for the next page. */
public record TimelinePage(
        List<TweetView> items,
        @JsonSerialize(using = ToStringSerializer.class) Long nextCursor) {
}
