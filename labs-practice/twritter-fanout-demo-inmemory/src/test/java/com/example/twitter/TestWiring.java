package com.example.twitter;

import com.example.twitter.config.TwitterProperties;
import com.example.twitter.repo.GraphRepository;
import com.example.twitter.repo.TimelineCache;
import com.example.twitter.repo.TweetRepository;
import com.example.twitter.repo.UserRepository;
import com.example.twitter.service.FanoutService;
import com.example.twitter.service.GraphService;
import com.example.twitter.service.SnowflakeIdGenerator;
import com.example.twitter.service.TimelineService;
import com.example.twitter.service.TweetService;
import com.example.twitter.service.UserService;

/**
 * Builds the real object graph by hand (no Spring) so unit tests stay fast.
 * Fan-out runs synchronously, so tests need no sleeps.
 */
public final class TestWiring {

    public final TwitterProperties props;
    public final SnowflakeIdGenerator ids;
    public final GraphRepository graphRepo;
    public final TweetRepository tweetRepo;
    public final TimelineCache cache;
    public final UserService users;
    public final GraphService graph;
    public final FanoutService fanout;
    public final TweetService tweets;
    public final TimelineService timelines;

    private TestWiring(int celebrityThreshold, int timelineMax) {
        props = new TwitterProperties(celebrityThreshold, timelineMax, 20, 100, 1, false);
        ids = new SnowflakeIdGenerator(props);
        UserRepository userRepo = new UserRepository();
        graphRepo = new GraphRepository();
        tweetRepo = new TweetRepository();
        cache = new TimelineCache(props);
        users = new UserService(userRepo, ids);
        graph = new GraphService(graphRepo, users, props);
        fanout = new FanoutService(graph, cache, props);
        tweets = new TweetService(tweetRepo, users, ids, fanout);
        timelines = new TimelineService(cache, tweetRepo, graph, tweets, users, props);
    }

    /** Celebrity threshold 3, timeline cap 800. */
    public static TestWiring create() {
        return new TestWiring(3, 800);
    }

    public static TestWiring create(int celebrityThreshold, int timelineMax) {
        return new TestWiring(celebrityThreshold, timelineMax);
    }

    public long newUser(String handle) {
        return users.create(handle, null).id();
    }

    /** Make every given user follow {@code followee}. */
    public void followAll(long followee, long... followers) {
        for (long f : followers) {
            graph.follow(f, followee);
        }
    }
}
