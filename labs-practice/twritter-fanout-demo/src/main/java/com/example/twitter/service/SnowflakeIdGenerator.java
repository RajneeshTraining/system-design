package com.example.twitter.service;

import com.example.twitter.config.TwitterProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 64-bit time-sortable IDs: 41 bits timestamp | 10 bits worker | 12 bits sequence.
 * Newest-first ordering is therefore just a descending sort on the ID.
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1735689600000L;   // 2025-01-01T00:00:00Z

    private final long workerId;
    private final ReentrantLock lock = new ReentrantLock();   // ReentrantLock is virtual-thread friendly
    private long lastTs = -1L;
    private long seq = 0L;

    public SnowflakeIdGenerator(TwitterProperties props) {
        long w = props.workerId();
        if (w < 0 || w > 1023) {
            throw new IllegalArgumentException("twitter.worker-id must be between 0 and 1023");
        }
        this.workerId = w;
    }

    public long next() {
        lock.lock();
        try {
            long ts = Math.max(System.currentTimeMillis(), lastTs);   // tolerate small clock regressions
            if (ts == lastTs) {
                seq = (seq + 1) & 4095;
                if (seq == 0) {                                       // 4096 ids used this millisecond
                    do {
                        Thread.onSpinWait();
                        ts = System.currentTimeMillis();
                    } while (ts <= lastTs);
                }
            } else {
                seq = 0;
            }
            lastTs = ts;
            return ((ts - EPOCH) << 22) | (workerId << 12) | seq;
        } finally {
            lock.unlock();
        }
    }
}
