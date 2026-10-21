package com.example.twitter.service;

import com.example.twitter.model.User;
import com.example.twitter.repo.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final SnowflakeIdGenerator ids;

    public UserService(UserRepository users, SnowflakeIdGenerator ids) {
        this.users = users;
        this.ids = ids;
    }

    public User create(String handle, String displayName) {
        String name = (displayName == null || displayName.isBlank()) ? handle : displayName.strip();
        return users.create(ids.next(), handle, name)
                .orElseThrow(() -> ApiException.conflict("handle '" + handle + "' is already taken"));
    }

    public User require(long id) {
        return users.find(id).orElseThrow(() -> ApiException.notFound("user " + id));
    }
}
