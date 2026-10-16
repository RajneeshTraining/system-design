package com.example.bloom.service;

import com.example.bloom.config.BloomProperties;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe holder around a Guava {@link BloomFilter} of normalized emails.
 *
 * <p>Key safety rule: until a filter is ready, {@link #mightContain(String)} returns {@code true}
 * ("fail open"), so requests simply fall through to the database. The filter can therefore never
 * cause a wrong "not found" answer, even during start-up or a rebuild.
 */
@Component
public class EmailBloomFilter {

    private static final int SNAPSHOT_MAGIC = 0xB100F117;

    private final BloomProperties props;

    /** Filter used to answer lookups. null = not ready yet (fail open). */
    private volatile BloomFilter<CharSequence> active;
    /** Filter being built by a rebuild; receives live inserts too so nothing is lost on swap. */
    private volatile BloomFilter<CharSequence> building;
    /** Highest row id observed; stored in the snapshot to allow delta catch-up. */
    private final AtomicLong maxId = new AtomicLong(0);

    public EmailBloomFilter(BloomProperties props) {
        this.props = props;
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    // ---- lookups / inserts -------------------------------------------------------------

    /** false = DEFINITELY not in the DB. true = maybe (go check the DB). */
    public boolean mightContain(String normalizedEmail) {
        BloomFilter<CharSequence> f = active;
        return f == null || f.mightContain(normalizedEmail);
    }

    /** Call after the row is committed to the DB. */
    public void put(String normalizedEmail, long id) {
        BloomFilter<CharSequence> a = active;
        if (a != null) a.put(normalizedEmail);
        BloomFilter<CharSequence> b = building;
        if (b != null) b.put(normalizedEmail);
        observeId(id);
    }

    public boolean isReady() {
        return active != null;
    }

    // ---- lifecycle used by BloomMaintenanceService -----------------------------------

    BloomFilter<CharSequence> newFilter() {
        return BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8),
                props.getExpectedInsertions(), props.getFpp());
    }

    BloomFilter<CharSequence> startBuilding() {
        BloomFilter<CharSequence> f = newFilter();
        this.building = f;
        return f;
    }

    void activateBuilt() {
        BloomFilter<CharSequence> b = this.building;
        if (b != null) {
            this.active = b;      // atomic swap
            this.building = null;
        }
    }

    void abortBuilding() {
        this.building = null;
    }

    /** Used to catch up directly into the active filter (adding is always safe). */
    BloomFilter<CharSequence> activeOrNull() {
        return active;
    }

    void observeId(long id) {
        maxId.accumulateAndGet(id, Math::max);
    }

    long maxObservedId() {
        return maxId.get();
    }

    // ---- stats --------------------------------------------------------------------------

    public Stats stats() {
        BloomFilter<CharSequence> f = active;
        if (f == null) {
            return new Stats(false, props.getExpectedInsertions(), props.getFpp(), 0, 0, maxId.get());
        }
        return new Stats(true, props.getExpectedInsertions(), props.getFpp(),
                f.approximateElementCount(), f.expectedFpp(), maxId.get());
    }

    public record Stats(boolean ready, long configuredCapacity, double configuredFpp,
                        long approximateElements, double currentExpectedFpp, long maxIdSeen) { }

    // ---- snapshot (persist to disk so restarts are fast) -----------------------------

    /** Writes atomically (temp file + move). Returns false if there is nothing to save. */
    public boolean saveSnapshot(Path path) throws IOException {
        BloomFilter<CharSequence> f = active;
        if (f == null) return false;
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeLong(props.getExpectedInsertions());
            out.writeDouble(props.getFpp());
            out.writeLong(maxId.get());
            f.writeTo(out);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /**
     * Loads a snapshot if it exists and was built with the same capacity/fpp as the current config.
     * @return the high-water-mark id stored in the snapshot, or -1 if no usable snapshot.
     */
    public long loadSnapshot(Path path) {
        if (!Files.isRegularFile(path)) return -1;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != SNAPSHOT_MAGIC) return -1;
            long cap = in.readLong();
            double fpp = in.readDouble();
            long hwm = in.readLong();
            if (cap != props.getExpectedInsertions() || Double.compare(fpp, props.getFpp()) != 0) {
                return -1; // config changed -> snapshot is the wrong size, rebuild instead
            }
            BloomFilter<CharSequence> f = BloomFilter.readFrom(in, Funnels.stringFunnel(StandardCharsets.UTF_8));
            observeId(hwm);
            this.active = f;
            return hwm;
        } catch (IOException | RuntimeException e) {
            return -1; // corrupt snapshot -> ignore, rebuild from DB
        }
    }
}
