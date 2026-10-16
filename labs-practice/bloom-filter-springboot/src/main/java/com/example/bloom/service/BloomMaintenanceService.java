package com.example.bloom.service;

import com.example.bloom.config.BloomProperties;
import com.example.bloom.repository.EmailView;
import com.example.bloom.repository.UserRepository;
import com.google.common.hash.BloomFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Builds, rebuilds, catches up and snapshots the filter. */
@Service
public class BloomMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(BloomMaintenanceService.class);

    private final EmailBloomFilter bloom;
    private final UserRepository repo;
    private final BloomProperties props;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BloomMaintenanceService(EmailBloomFilter bloom, UserRepository repo, BloomProperties props) {
        this.bloom = bloom;
        this.repo = repo;
        this.props = props;
    }

    /** Runs once the app is up (and after any demo seeding). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Runnable task = this::initialise;
        if (props.isWarmupAsync()) {
            Thread t = new Thread(task, "bloom-warmup");
            t.setDaemon(true);
            t.start();
        } else {
            task.run();
        }
    }

    void initialise() {
        if (!running.compareAndSet(false, true)) return;
        try {
            if (props.isSnapshotEnabled()) {
                long hwm = bloom.loadSnapshot(Path.of(props.getSnapshotPath()));
                if (hwm >= 0) {
                    long from = Math.max(0, hwm - props.getCatchUpSafetyMargin());
                    log.info("Loaded Bloom snapshot (high-water id {}). Catching up from id {}", hwm, from);
                    scan(bloom.activeOrNull(), from);
                    return;
                }
                log.info("No usable Bloom snapshot; doing a full build");
            }
            fullBuild();
        } catch (RuntimeException e) {
            log.error("Bloom initialisation failed; filter stays disabled (lookups go straight to DB)", e);
            bloom.abortBuilding();
        } finally {
            running.set(false);
        }
    }

    /** Rebuild from scratch into a side filter, then swap atomically. Safe to call while serving traffic. */
    public boolean rebuildAsync() {
        if (!running.compareAndSet(false, true)) return false;
        Thread t = new Thread(() -> {
            try {
                fullBuild();
            } catch (RuntimeException e) {
                log.error("Bloom rebuild failed; keeping previous filter", e);
                bloom.abortBuilding();
            } finally {
                running.set(false);
            }
        }, "bloom-rebuild");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public boolean isBuildRunning() {
        return running.get();
    }

    private void fullBuild() {
        long started = System.currentTimeMillis();
        BloomFilter<CharSequence> target = bloom.startBuilding();
        long rows = scan(target, 0);
        bloom.activateBuilt();
        log.info("Bloom filter built: {} rows in {} ms", rows, System.currentTimeMillis() - started);
    }

    /** Keyset-paginated scan: id > last ORDER BY id LIMIT n. */
    private long scan(BloomFilter<CharSequence> target, long fromId) {
        long last = fromId;
        long total = 0;
        while (true) {
            List<EmailView> batch = repo.findByIdGreaterThanOrderByIdAsc(last, PageRequest.of(0, props.getWarmupBatchSize()));
            if (batch.isEmpty()) break;
            for (EmailView row : batch) {
                target.put(EmailBloomFilter.normalize(row.getEmail()));
                last = row.getId();
            }
            bloom.observeId(last);
            total += batch.size();
            if (total % (props.getWarmupBatchSize() * 20L) < batch.size()) {
                log.info("Bloom scan progress: {} rows (last id {})", total, last);
            }
        }
        return total;
    }

    // ---- snapshots ---------------------------------------------------------------------

    @Scheduled(initialDelayString = "${bloom.snapshot-interval-ms:600000}",
               fixedDelayString = "${bloom.snapshot-interval-ms:600000}")
    public void scheduledSnapshot() {
        if (props.isSnapshotEnabled()) snapshotNow();
    }

    @PreDestroy
    void onShutdown() {
        if (props.isSnapshotEnabled()) snapshotNow();
    }

    public boolean snapshotNow() {
        try {
            boolean saved = bloom.saveSnapshot(Path.of(props.getSnapshotPath()));
            if (saved) log.info("Bloom snapshot saved to {}", props.getSnapshotPath());
            return saved;
        } catch (Exception e) {
            log.warn("Could not save Bloom snapshot", e);
            return false;
        }
    }
}
