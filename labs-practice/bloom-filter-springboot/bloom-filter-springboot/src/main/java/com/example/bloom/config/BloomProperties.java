package com.example.bloom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bloom filter settings. See docs/SIZING.md for how to choose the numbers.
 */
@ConfigurationProperties(prefix = "bloom")
public class BloomProperties {

    /** Capacity the filter is sized for. Set to (current rows + growth headroom), e.g. 1.5 billion. */
    private long expectedInsertions = 1_000_000L;

    /** Target false-positive probability at full capacity (0.01 = 1%). */
    private double fpp = 0.01;

    /** Rows read from the DB per batch while (re)building the filter. */
    private int warmupBatchSize = 50_000;

    /** Build the filter in a background thread so the app starts immediately (lookups fail-open to the DB). */
    private boolean warmupAsync = true;

    /** Persist the filter to disk so restarts only need to scan newly added rows. */
    private boolean snapshotEnabled = false;
    private String snapshotPath = "./data/email-bloom.snapshot";

    /** How often to persist the snapshot (ms). */
    private long snapshotIntervalMs = 600_000L;

    /** On snapshot load, re-scan this many ids below the saved high-water mark (guards out-of-order commits). */
    private long catchUpSafetyMargin = 10_000L;

    public long getExpectedInsertions() { return expectedInsertions; }
    public void setExpectedInsertions(long v) { this.expectedInsertions = v; }
    public double getFpp() { return fpp; }
    public void setFpp(double v) { this.fpp = v; }
    public int getWarmupBatchSize() { return warmupBatchSize; }
    public void setWarmupBatchSize(int v) { this.warmupBatchSize = v; }
    public boolean isWarmupAsync() { return warmupAsync; }
    public void setWarmupAsync(boolean v) { this.warmupAsync = v; }
    public boolean isSnapshotEnabled() { return snapshotEnabled; }
    public void setSnapshotEnabled(boolean v) { this.snapshotEnabled = v; }
    public String getSnapshotPath() { return snapshotPath; }
    public void setSnapshotPath(String v) { this.snapshotPath = v; }
    public long getSnapshotIntervalMs() { return snapshotIntervalMs; }
    public void setSnapshotIntervalMs(long v) { this.snapshotIntervalMs = v; }
    public long getCatchUpSafetyMargin() { return catchUpSafetyMargin; }
    public void setCatchUpSafetyMargin(long v) { this.catchUpSafetyMargin = v; }
}
