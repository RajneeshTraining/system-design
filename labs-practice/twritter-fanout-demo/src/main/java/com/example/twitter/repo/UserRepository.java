package com.example.twitter.repo;

import com.example.twitter.model.User;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory stand-in for the PostgreSQL accounts table. */
@Repository
public class UserRepository {

    private final Map<Long, User> byId = new ConcurrentHashMap<>();
    private final Map<String, Long> byHandle = new ConcurrentHashMap<>();   // lower-cased handle -> id

    /** @return the new user, or empty if the handle is already taken. */
    public Optional<User> create(long id, String handle, String displayName) {
        if (byHandle.putIfAbsent(handle.toLowerCase(), id) != null) {
            return Optional.empty();
        }
        User user = new User(id, handle, displayName, Instant.now());
        byId.put(id, user);
        return Optional.of(user);
    }

    public Optional<User> find(long id) {
        return Optional.ofNullable(byId.get(id));
    }
}
