-- Schema for the PostgreSQL profile. docker-compose mounts this file so it runs automatically.
-- In a real project manage this with Flyway or Liquibase.

CREATE TABLE IF NOT EXISTS users (
    id      BIGSERIAL PRIMARY KEY,
    email   VARCHAR(320) NOT NULL,
    name    VARCHAR(200) NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)   -- creates the B-tree index; REQUIRED even with a Bloom filter
);

-- At billions of rows consider declarative hash partitioning on email, e.g.:
--   CREATE TABLE users (...) PARTITION BY HASH (email);
-- (the unique constraint must then include the partition key, which email already is.)
