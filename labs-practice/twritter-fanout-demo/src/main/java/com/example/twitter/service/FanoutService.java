package com.example.twitter.service;

import com.example.twitter.config.TwitterProperties;
import com.example.twitter.model.Tweet;
import com.example.twitter.repo.TimelineCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Write-time half of the hybrid timeline.
 * <ul>
 *   <li>Normal author: push the tweet ID into every follower's timeline (and the author's own).</li>
 *   <li>Celebrity: push nothing; followers pull the tweet at read time (see TimelineService).</li>
 * </ul>
 * In production this work is a Kafka consumer; here a virtual-thread executor plays that role.
 */
@Service
public class FanoutService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);

    private final GraphService graph;
    private final TimelineCache timelines;
    private final boolean async;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FanoutService(GraphService graph, TimelineCache timelines, TwitterProperties props) {
        this.graph = graph;
        this.timelines = timelines;
        this.async = props.fanoutAsync();
    }

    public void fanOut(Tweet tweet) {
        if (graph.isCelebrity(tweet.authorId())) {
            log.info("tweet {} by celebrity {}: skipping push fan-out (followers pull at read time)",
                    tweet.id(), tweet.authorId());
            timelines.push(tweet.authorId(), tweet.id());
            return;
        }
        Runnable job = () -> {
            var followers = graph.followersOf(tweet.authorId());
            timelines.push(tweet.authorId(), tweet.id());
            followers.forEach(f -> timelines.push(f, tweet.id()));
            log.info("tweet {} by {}: pushed to {} follower timelines", tweet.id(), tweet.authorId(), followers.size());
        };
        if (async) {
            executor.submit(job);
        } else {
            job.run();
        }
    }

    @Override
    public void destroy() {
        executor.close();
    }
}
