package com.example.twitter.model;

import java.time.Instant;

public record User(long id, String handle, String displayName, Instant createdAt) {
}
