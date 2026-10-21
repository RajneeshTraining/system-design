package com.example.twitter.service;

import com.example.twitter.config.TwitterProperties;
import com.example.twitter.model.TimelinePage;
import com.example.twitter.model.TweetView;
import com.example.twitter.repo.TimelineCache;
import com.example.twitter.repo.TweetRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read-time half of the hybrid timeline:
 * pushed IDs (normal authors) + pulled IDs (celebrities the user follows), merged newest-first.
 */
@Service
public class TimelineService {

    private final TimelineCache timelines;
    private final TweetRepository tweets;
    private final GraphService graph;
    private final TweetService tweetService;
    private final UserService users;
    private final TwitterProperties props;

    public TimelineService(TimelineCache timelines, TweetRepository tweets, GraphService graph,
                           TweetService tweetService, UserService users, TwitterProperties props) {
        this.timelines = timelines;
        this.tweets = tweets;
        this.graph = graph;
        this.tweetService = tweetService;
        this.users = users;
        this.props = props;
    }

    /** Home timeline: people the user follows, newest first, cursor-paged by tweet id. */
    public TimelinePage home(long userId, Long maxId, Integer limit) {
        users.require(userId);
        int n = pageSize(limit);

        List<Long> pushed = timelines.read(userId, maxId, n);
        List<Long> pulled = graph.followedCelebrities(userId).stream()
                .flatMap(celebrity -> tweets.recentByAuthor(celebrity, maxId, n).stream())
                .toList();

        List<Long> merged = Stream.concat(pushed.stream(), pulled.stream())
                .distinct()
                .sorted(Comparator.reverseOrder())      // Snowflake ids sort by creation time
                .limit(n)
                .toList();

        return page(merged, n);
    }

    /** Profile timeline: one author's own tweets. */
    public TimelinePage profile(long userId, Long maxId, Integer limit) {
        users.require(userId);
        int n = pageSize(limit);
        return page(tweets.recentByAuthor(userId, maxId, n), n);
    }

    private TimelinePage page(List<Long> ids, int n) {
        List<TweetView> items = ids.stream()
                .map(tweetService::find)               // drops deleted tweets still referenced by caches
                .flatMap(java.util.Optional::stream)
                .toList();
        Long next = (ids.size() == n) ? ids.getLast() : null;   // Java 21 SequencedCollection#getLast
        return new TimelinePage(items, next);
    }

    private int pageSize(Integer limit) {
        int n = (limit == null) ? props.defaultPageSize() : limit;
        return Math.max(1, Math.min(n, props.maxPageSize()));
    }
}
