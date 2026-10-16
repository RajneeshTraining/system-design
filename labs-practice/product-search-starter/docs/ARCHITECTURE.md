# Architecture and implementation notes

## Two interchangeable search stores

The `ProductSearchStore` interface isolates search behavior from the controller.

- `InMemoryProductSearchStore` is selected by default (`app.search.mode=memory`). It filters and sorts eight deterministic sample products using Java collections. It requires no database or search server.
- `OpenSearchProductSearchStore` is selected with `app.search.mode=opensearch`. It talks to OpenSearch's HTTP API using Java `HttpClient`, creates a local demo index if it does not exist, seeds sample documents on first index creation, and uses a bool query with filters, sorting, and terms aggregations.

The same endpoint contract is used in both modes. The in-memory implementation is intentionally small and is not intended for large catalogues. Its `RELEVANCE` mode is only a deterministic demo order; the OpenSearch implementation uses `_score` for text relevance.

## Request flow

1. Controller binds query parameters and applies basic bean validation.
2. Search store builds filters for category, colour, brand, price, rating, and stock.
3. Store applies a whitelisted sort mode and a stable product ID tie-breaker.
4. Store returns product cards, total count, page metadata, and brand/colour facets.

## Search document

The product search document is denormalized to contain fields needed by the listing page. A production marketplace may require one document per sellable offer/SKU rather than one per product family. If fields such as seller, price, inventory, or delivery promise are offer-specific, ensure all filter and sort conditions apply to the same offer. Consider OpenSearch nested mappings or a separate offer search model when needed.

## Data freshness and source of truth

The demo seeds fixed data; it does not include a catalogue database, Kafka, or event publisher. For production, the transactional catalogue remains the source of truth and the search index is a derived read model. Use a transactional outbox or CDC to publish changes, durable event delivery, idempotent consumers, version checks against out-of-order updates, retries, dead-letter handling, and reconciliation.

Always validate final price, inventory, seller eligibility, and promotion rules at cart/checkout. A search result is not a stock reservation or price guarantee.

## Production hardening checklist

- OpenSearch TLS, authentication, least privilege, network isolation, snapshots, monitoring, and capacity planning.
- Query allowlists, limits on filters/aggregations/page size, rate limiting, and request timeouts.
- Cursor pagination with `search_after` and optionally Point in Time for deep traversal.
- Explicit missing-value sort policy and disjunctive facet semantics where required.
- Versioned indices and aliases for safe mapping changes/reindexing.
- Integration tests against a pinned OpenSearch version and performance/load tests with representative data.
- Do not use the Compose security-disabled configuration outside an isolated local environment.
