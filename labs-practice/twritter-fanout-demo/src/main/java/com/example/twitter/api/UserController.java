package com.example.twitter.api;

import com.example.twitter.model.User;
import com.example.twitter.service.GraphService;
import com.example.twitter.service.UserService;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    record CreateUserRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_]{1,15}$", message = "must be 1-15 letters, digits or underscores")
            String handle,
            @Size(max = 50) String displayName) {
    }

    record UserResponse(
            @JsonSerialize(using = ToStringSerializer.class) long id,
            String handle,
            String displayName,
            Instant createdAt,
            int followers,
            int following,
            boolean celebrity) {
    }

    private final UserService users;
    private final GraphService graph;

    UserController(UserService users, GraphService graph) {
        this.users = users;
        this.graph = graph;
    }

    @PostMapping
    ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        User u = users.create(req.handle(), req.displayName());
        return ResponseEntity.created(URI.create("/api/v1/users/" + u.id())).body(toResponse(u));
    }

    @GetMapping("/{id}")
    UserResponse get(@PathVariable long id) {
        return toResponse(users.require(id));
    }

    @PostMapping("/{id}/follow/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void follow(@PathVariable long id, @PathVariable long targetId) {
        graph.follow(id, targetId);
    }

    @DeleteMapping("/{id}/follow/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unfollow(@PathVariable long id, @PathVariable long targetId) {
        graph.unfollow(id, targetId);
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(u.id(), u.handle(), u.displayName(), u.createdAt(),
                graph.followerCount(u.id()), graph.followingCount(u.id()), graph.isCelebrity(u.id()));
    }
}
