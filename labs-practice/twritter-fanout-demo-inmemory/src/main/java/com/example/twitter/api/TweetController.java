package com.example.twitter.api;

import com.example.twitter.model.TweetView;
import com.example.twitter.service.TweetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * DEMO NOTE: there is no authentication. The acting user is passed explicitly
 * (authorId in the body, userId as a query parameter). Production resolves it from the JWT.
 */
@RestController
@RequestMapping("/api/v1/tweets")
class TweetController {

    record CreateTweetRequest(
            @NotNull Long authorId,
            @NotBlank @Size(max = 280) String text,
            Long inReplyToId) {
    }

    private final TweetService tweets;

    TweetController(TweetService tweets) {
        this.tweets = tweets;
    }

    @PostMapping
    ResponseEntity<TweetView> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTweetRequest req) {
        TweetView t = tweets.create(req.authorId(), req.text(), req.inReplyToId(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/tweets/" + t.id())).body(t);
    }

    @GetMapping("/{id}")
    TweetView get(@PathVariable long id) {
        return tweets.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, @RequestParam long userId) {
        tweets.delete(id, userId);
    }

    @PostMapping("/{id}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void like(@PathVariable long id, @RequestParam long userId) {
        tweets.like(id, userId);
    }
}
