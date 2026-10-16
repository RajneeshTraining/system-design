package com.example.bloom.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional: bulk-inserts user{n}@example.com rows so you can try the filter without your own data.
 * Enable with demo.seed.enabled=true (on by default for the H2 quick start).
 */
@Component
@ConditionalOnProperty(name = "demo.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final int BATCH = 10_000;

    private final JdbcTemplate jdbc;
    private final long rows;

    public DemoDataSeeder(JdbcTemplate jdbc, @Value("${demo.seed.rows:200000}") long rows) {
        this.jdbc = jdbc;
        this.rows = rows;
    }

    @Override
    public void run(String... args) {
        Long existing = jdbc.queryForObject("select count(*) from users", Long.class);
        if (existing != null && existing >= rows) {
            log.info("Demo seed skipped: table already has {} rows", existing);
            return;
        }
        log.info("Seeding {} demo users ...", rows);
        long start = existing == null ? 0 : existing;
        for (long i = start; i < rows; i += BATCH) {
            long from = i, to = Math.min(i + BATCH, rows);
            List<Object[]> args2 = new ArrayList<>((int) (to - from));
            for (long n = from; n < to; n++) {
                args2.add(new Object[]{"user" + n + "@example.com", "User " + n});
            }
            jdbc.batchUpdate("insert into users (email, name) values (?, ?)", args2);
        }
        log.info("Demo seed done. Emails look like user0@example.com ... user{}@example.com", rows - 1);
    }
}
