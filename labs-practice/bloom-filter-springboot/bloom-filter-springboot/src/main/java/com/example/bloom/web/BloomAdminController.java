package com.example.bloom.web;

import com.example.bloom.service.BloomMaintenanceService;
import com.example.bloom.service.EmailBloomFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational endpoints. In production put these behind authentication / an internal-only route.
 */
@RestController
@RequestMapping("/api/admin/bloom")
public class BloomAdminController {

    private final EmailBloomFilter bloom;
    private final BloomMaintenanceService maintenance;
    private final MeterRegistry registry;

    public BloomAdminController(EmailBloomFilter bloom, BloomMaintenanceService maintenance, MeterRegistry registry) {
        this.bloom = bloom;
        this.maintenance = maintenance;
        this.registry = registry;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filter", bloom.stats());
        out.put("buildRunning", maintenance.isBuildRunning());
        double absent = count("definitely_absent");
        double hit = count("db_hit");
        double fp = count("false_positive");
        out.put("lookups", Map.of("definitelyAbsent", (long) absent, "dbHit", (long) hit, "falsePositive", (long) fp));
        double dbCalls = hit + fp;
        double total = absent + dbCalls;
        out.put("dbCallsAvoidedPercent", total == 0 ? 0 : Math.round(10000.0 * absent / total) / 100.0);
        // Observed false-positive rate = false positives / all lookups for keys that truly don't exist
        double trulyAbsent = absent + fp;
        out.put("observedFalsePositiveRate", trulyAbsent == 0 ? 0 : fp / trulyAbsent);
        return out;
    }

    /** Rebuild from the DB in the background and swap atomically. */
    @PostMapping("/rebuild")
    public ResponseEntity<String> rebuild() {
        return maintenance.rebuildAsync()
                ? ResponseEntity.accepted().body("Rebuild started")
                : ResponseEntity.status(409).body("A build is already running");
    }

    @PostMapping("/snapshot")
    public ResponseEntity<String> snapshot() {
        return maintenance.snapshotNow()
                ? ResponseEntity.ok("Snapshot saved")
                : ResponseEntity.status(409).body("Nothing saved (filter not ready or snapshot failed)");
    }

    private double count(String result) {
        var c = registry.find("bloom.lookup").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }
}
