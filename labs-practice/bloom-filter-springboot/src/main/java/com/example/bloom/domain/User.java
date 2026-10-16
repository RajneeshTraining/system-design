package com.example.bloom.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Always stored normalized (trimmed + lower-case). The unique index on this column is still required. */
    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 200)
    private String name;

    protected User() { }

    public User(String email, String name) {
        this.email = email;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
}
