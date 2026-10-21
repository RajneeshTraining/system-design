package com.example.twitter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.twitter.config.TwitterProperties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    private static TwitterProperties props(long workerId) {
        return new TwitterProperties(3, 800, 20, 100, workerId, false);
    }

    @Test
    void idsAreStrictlyIncreasingOnOneThread() {
        var gen = new SnowflakeIdGenerator(props(1));
        long prev = -1;
        for (int i = 0; i < 10_000; i++) {
            long id = gen.next();
            assertTrue(id > prev, "id must be greater than the previous one");
            prev = id;
        }
    }

    @Test
    void idsAreUniqueAcrossVirtualThreads() {
        var gen = new SnowflakeIdGenerator(props(1));
        Set<Long> all = ConcurrentHashMap.newKeySet();
        int threads = 32, perThread = 2_000;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        all.add(gen.next());
                    }
                });
            }
        }
        assertEquals(threads * perThread, all.size());
    }

    @Test
    void workerIdIsEncodedInBits12To21() {
        long id = new SnowflakeIdGenerator(props(5)).next();
        assertEquals(5, (id >> 12) & 1023);
    }

    @Test
    void rejectsWorkerIdOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(props(1024)));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(props(-1)));
    }
}
