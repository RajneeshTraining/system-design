package com.example.bloom.repository;

import com.example.bloom.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Keyset pagination (WHERE id > ? ORDER BY id LIMIT n). Uses the primary-key index, so the
     * cost per batch stays flat even at billions of rows (OFFSET pagination would not).
     */
    List<EmailView> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
}
