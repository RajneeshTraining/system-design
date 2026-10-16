package com.example.bloom.service;

import com.example.bloom.config.BloomProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EmailBloomFilterTest {

    private static EmailBloomFilter newFilter(long capacity, double fpp) {
        BloomProperties p = new BloomProperties();
        p.setExpectedInsertions(capacity);
        p.setFpp(fpp);
        EmailBloomFilter f = new EmailBloomFilter(p);
        f.startBuilding();
        f.activateBuilt();
        return f;
    }

    @Test
    void failsOpenUntilReady() {
        BloomProperties p = new BloomProperties();
        EmailBloomFilter f = new EmailBloomFilter(p);
        assertThat(f.isReady()).isFalse();
        assertThat(f.mightContain("anything@example.com")).isTrue(); // never a wrong "not found"
    }

    @Test
    void neverHasFalseNegatives() {
        EmailBloomFilter f = newFilter(100_000, 0.01);
        for (int i = 0; i < 100_000; i++) f.put("user" + i + "@example.com", i);
        for (int i = 0; i < 100_000; i++) {
            assertThat(f.mightContain("user" + i + "@example.com")).isTrue();
        }
    }

    @Test
    void falsePositiveRateStaysNearTarget() {
        EmailBloomFilter f = newFilter(100_000, 0.01);
        for (int i = 0; i < 100_000; i++) f.put("user" + i + "@example.com", i);
        int fp = 0, probes = 100_000;
        for (int i = 0; i < probes; i++) if (f.mightContain("absent" + i + "@nowhere.org")) fp++;
        double rate = (double) fp / probes;
        assertThat(rate).isLessThan(0.02); // target 1%, allow 2x headroom for statistical noise
    }

    @Test
    void normalizesCase() {
        assertThat(EmailBloomFilter.normalize("  Jane@Example.COM ")).isEqualTo("jane@example.com");
    }

    @Test
    void snapshotRoundTrip(@TempDir Path dir) throws Exception {
        EmailBloomFilter a = newFilter(10_000, 0.01);
        for (int i = 0; i < 5_000; i++) a.put("u" + i + "@x.com", i);
        Path file = dir.resolve("snap.bin");
        assertThat(a.saveSnapshot(file)).isTrue();

        BloomProperties p = new BloomProperties();
        p.setExpectedInsertions(10_000);
        p.setFpp(0.01);
        EmailBloomFilter b = new EmailBloomFilter(p);
        long hwm = b.loadSnapshot(file);
        assertThat(hwm).isEqualTo(4_999);
        assertThat(b.isReady()).isTrue();
        for (int i = 0; i < 5_000; i++) assertThat(b.mightContain("u" + i + "@x.com")).isTrue();
    }

    @Test
    void snapshotWithDifferentConfigIsIgnored(@TempDir Path dir) throws Exception {
        EmailBloomFilter a = newFilter(10_000, 0.01);
        a.put("u@x.com", 1);
        Path file = dir.resolve("snap.bin");
        a.saveSnapshot(file);

        BloomProperties other = new BloomProperties();
        other.setExpectedInsertions(20_000);   // config changed
        other.setFpp(0.01);
        EmailBloomFilter b = new EmailBloomFilter(other);
        assertThat(b.loadSnapshot(file)).isEqualTo(-1);
        assertThat(b.isReady()).isFalse();
    }
}
