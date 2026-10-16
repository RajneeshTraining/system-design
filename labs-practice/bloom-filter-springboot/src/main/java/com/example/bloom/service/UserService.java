package com.example.bloom.service;

import com.example.bloom.domain.User;
import com.example.bloom.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository repo;
    private final EmailBloomFilter bloom;

    private final Counter definitelyAbsent;   // answered by Bloom alone, DB untouched
    private final Counter dbHit;              // Bloom said maybe, DB found the row
    private final Counter falsePositive;      // Bloom said maybe, DB did not have it

    public UserService(UserRepository repo, EmailBloomFilter bloom, MeterRegistry registry) {
        this.repo = repo;
        this.bloom = bloom;
        this.definitelyAbsent = Counter.builder("bloom.lookup").tag("result", "definitely_absent").register(registry);
        this.dbHit = Counter.builder("bloom.lookup").tag("result", "db_hit").register(registry);
        this.falsePositive = Counter.builder("bloom.lookup").tag("result", "false_positive").register(registry);
    }

    /** The hot path: look a user up by email. */
    public Optional<User> findByEmail(String rawEmail) {
        String email = EmailBloomFilter.normalize(rawEmail);

        if (!bloom.mightContain(email)) {
            definitelyAbsent.increment();     // no DB call at all
            return Optional.empty();
        }

        Optional<User> user = repo.findByEmail(email);
        (user.isPresent() ? dbHit : falsePositive).increment();
        return user;
    }

    public User create(String rawEmail, String name) {
        String email = EmailBloomFilter.normalize(rawEmail);

        // Fast duplicate check: if Bloom says "definitely new", skip the extra DB round trip.
        // The unique index remains the real guarantee against duplicates.
        if (bloom.mightContain(email) && repo.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        try {
            User saved = repo.saveAndFlush(new User(email, name));
            bloom.put(email, saved.getId());   // only after the row is committed
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEmailException(email);
        }
    }

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email) {
            super("Email already registered: " + email);
        }
    }
}
