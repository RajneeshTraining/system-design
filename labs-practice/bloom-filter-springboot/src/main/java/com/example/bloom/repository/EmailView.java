package com.example.bloom.repository;

/** Lightweight projection used when scanning the table to build the filter. */
public interface EmailView {
    Long getId();
    String getEmail();
}
