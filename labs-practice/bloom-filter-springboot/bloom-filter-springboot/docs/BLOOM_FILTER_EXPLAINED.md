# Bloom Filters Explained (Step by Step)

## 1. The problem

You have a `users` table with billions of rows and a query like:

```sql
SELECT * FROM users WHERE email = 'someone@example.com';
```

Even with a B-tree index, each lookup costs several page reads, and at billions of rows the index no longer fits in
RAM, so many of those reads are real disk/network I/O. That is acceptable for *hits*. It is wasteful for *misses*:

* sign-up form checking "is this email already taken?" (most are not)
* login attempts with mistyped or non-existent emails
* bots / credential-stuffing / enumeration traffic hammering random emails
* "have we seen this before?" checks in pipelines

Every miss still pays the full index lookup, only to return nothing.

**Idea:** keep a tiny in-memory structure that can say *"this email is definitely not in the table"* instantly.

## 2. What is a Bloom filter?

A Bloom filter is a **bit array** (all zeros at the start) plus **k hash functions**. It answers one question:

> "Is element X in the set?"

with one of two answers:

| Answer | Meaning |
|---|---|
| **No** | X is **definitely not** in the set (100% certain) |
| **Maybe** | X **might** be in the set (small chance of being wrong) |

It never gives a false "No" (no false negatives). It occasionally gives a false "Maybe" (a false positive).

## 3. How it works — a worked example

Bit array of 10 bits, 3 hash functions.

**Adding `a@x.com`:** hash it 3 times → positions 1, 4, 7 → set those bits to 1.

```
index: 0 1 2 3 4 5 6 7 8 9
bits : 0 1 0 0 1 0 0 1 0 0
```

**Adding `b@x.com`:** positions 2, 4, 9 → set them.

```
bits : 0 1 1 0 1 0 0 1 0 1
```

**Checking `c@x.com`:** hashes → 0, 5, 8. Bit 0 is `0` → at least one bit is 0 → **definitely not present**. ✅

**Checking `d@x.com`:** hashes → 1, 4, 9. All three bits are `1` → **maybe present**. These bits were set by *other*
emails, so this might be a false positive. Only the database can confirm.

Key facts that fall out of this:

* To say "No", one zero bit is enough. Zero bits can never be wrong, because bits are only ever set, never cleared.
* To say "Maybe", all k bits must be 1. They could have been set by different elements → false positive.
* You cannot list the contents or retrieve an element. It stores only bits, not emails (no personal data in memory).
* You cannot delete (clearing a bit could break other elements that share it).

## 4. Why it is significant

| Property | Bloom filter | HashSet of emails | DB index |
|---|---|---|---|
| Memory for 1B emails | **~1.2 GB** (1% FPP) | ~100+ GB | n/a (disk, hundreds of GB) |
| Lookup cost | O(k) ≈ 7 hashes, in memory | O(1) but huge memory | several I/Os, ms |
| False negatives | never | never | never |
| False positives | tunable (e.g. 1%) | never | never |
| Stores the data | no (privacy-friendly) | yes | yes |

Roughly **~10 bits per element** buys a 1% error rate, regardless of how long the emails are.

## 5. The trade-off you control

Three numbers are linked: `n` (elements), `m` (bits), `p` (false-positive probability).

```
m = -n · ln(p) / (ln 2)²        bits needed
k = (m / n) · ln 2               optimal hash count
```

| Target FPP | Bits per email | Hashes (k) | 1 billion emails |
|---|---|---|---|
| 1 % | 9.6 | 7 | 1.2 GB |
| 0.1 % | 14.4 | 10 | 1.8 GB |
| 0.01 % | 19.2 | 13 | 2.4 GB |

Guava computes `m` and `k` for you from `(n, p)`. Details in [`SIZING.md`](SIZING.md).

## 6. Where Bloom filters are used in the real world

* **Databases / storage engines:** Cassandra, HBase, RocksDB, LevelDB skip SSTable files that definitely lack a key.
* **Web caches / CDNs:** Akamai-style "cache only on second request" to avoid caching one-hit wonders.
* **Browsers:** Safe-browsing style "is this URL possibly malicious?" prefilters.
* **Distributed systems:** avoiding network calls for keys that cannot exist.
* **Spam, dedup, crawlers:** "have I seen this URL / message-id before?"
* **Username/email availability checks** — our use case.

## 7. Applying it to "find a user by email"

```
lookup(email):
    e = normalize(email)                 # trim + lower-case, always the same way
    if !bloom.mightContain(e): return NOT_FOUND     # no DB call (most misses end here)
    row = db.findByEmail(e)              # index lookup
    return row or NOT_FOUND              # NOT_FOUND here = a false positive, harmless
```

Expected result with a 1% filter: of 100 lookups for non-existent emails, ~99 return instantly from memory and ~1
reaches the DB. Lookups for existing emails behave exactly as before (plus a negligible in-memory check).

On insert: save the row → commit → `bloom.put(email)`.

## 8. When it is a good fit — and when it is not

**Good fit**
* Many lookups are for keys that do not exist (sign-up checks, login typos, abuse traffic).
* The data set is large and mostly append-only.
* A small, bounded false-positive rate is acceptable (it only costs one extra DB call).

**Poor fit**
* Almost all lookups hit existing rows (nothing to save).
* The set has heavy deletes/updates of the key (use a counting/Cuckoo filter, or rebuild regularly).
* You need exact answers from the structure itself (it is only a pre-filter).
* The table is small enough that the index sits entirely in RAM already.

## 9. Common mistakes

1. **Under-sizing `n`.** Inserting 3× the planned capacity pushes a 1% filter towards ~44% false positives (measured).
2. **Different normalization** on write vs. read (`Foo@x.com` vs `foo@x.com`) → false *negatives* from the user's view.
3. **Using the filter as the source of truth.** It is a cache in front of the DB; keep the unique index.
4. **Serving traffic before the filter is complete** and answering "No" — must fail open until built.
5. **Inserting into the filter before the DB commit succeeds.**
