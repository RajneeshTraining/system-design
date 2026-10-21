package com.example.twitter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunables, bound from the {@code twitter.*} keys in application.yml. */
@ConfigurationProperties(prefix = "twitter")
public record TwitterProperties(
        @DefaultValue("3") int celebrityThreshold,
        @DefaultValue("800") int timelineMax,
        @DefaultValue("20") int defaultPageSize,
        @DefaultValue("100") int maxPageSize,
        @DefaultValue("1") long workerId,
        @DefaultValue("true") boolean fanoutAsync) {
}
