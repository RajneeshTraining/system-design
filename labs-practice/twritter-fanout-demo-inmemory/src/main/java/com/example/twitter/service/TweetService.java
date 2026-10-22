package com.example.twitter.service;

import com.example.twitter.model.Tweet;
import com.example.twitter.model.TweetView;
import com.example.twitter.repo.TweetRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class TweetService {

    private final TweetRepository tweets;
    private final UserService users;
    private final SnowflakeIdGenerator ids;
    private final FanoutService fanout;

    public TweetService(TweetRepository tweets, UserService users, SnowflakeIdGenerator ids, FanoutService fanout) {
        this.tweets = tweets;
        this.users = users;
        this.ids = ids;
        this.fanout = fanout;
    }

    /** Create a tweet. A repeated call with the same idempotency key returns the original tweet. */
    public TweetView create(long authorId, String text, Long inReplyToId, String idempotencyKey) {
        users.require(authorId);
        if (inReplyToId != null) {
            tweets.find(inReplyToId).filter(t -> !t.deleted())
                    .orElseThrow(() -> ApiException.notFound("tweet " + inReplyToId));
        }
        long id = ids.next();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Long existing = tweets.registerIdempotencyKey(authorId + ":" + idempotencyKey, id);
            if (existing != null) {
                Tweet original = tweets.find(existing)
                        .orElseThrow(() -> ApiException.conflict("a request with this Idempotency-Key is still in progress"));
                return view(original);
            }
        }
        Tweet tweet = new Tweet(id, authorId, text.strip(), inReplyToId, Instant.now(), false);
        tweets.save(tweet);
        fanout.fanOut(tweet);
        return view(tweet);
    }

    public TweetView get(long id) {
        return find(id).orElseThrow(() -> ApiException.notFound("tweet " + id));
    }

    public void delete(long tweetId, long requesterId) {
        Tweet t = tweets.find(tweetId).filter(x -> !x.deleted())
                .orElseThrow(() -> ApiException.notFound("tweet " + tweetId));
        if (t.authorId() != requesterId) {
            throw ApiException.forbidden("only the author can delete a tweet");
        }
        tweets.markDeleted(tweetId);   // timelines drop dangling IDs at read time
    }

    public void like(long tweetId, long userId) {
        users.require(userId);
        tweets.find(tweetId).filter(t -> !t.deleted())
                .orElseThrow(() -> ApiException.notFound("tweet " + tweetId));
        tweets.like(tweetId, userId);   // idempotent
    }

    /** Hydrate one tweet; empty if it is missing or deleted. */
    public Optional<TweetView> find(long id) {
        return tweets.find(id).filter(t -> !t.deleted()).map(this::view);
    }

    private TweetView view(Tweet t) {
        String handle = users.require(t.authorId()).handle();
        return new TweetView(t.id(), t.authorId(), handle, t.text(), t.inReplyToId(), t.createdAt(),
                tweets.likeCount(t.id()));
    }
}
