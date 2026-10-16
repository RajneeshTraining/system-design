# Multi-Criteria Product Sorting at E-Commerce Scale

Oct 16, 2026 · @RKG

Sorting a catalogue of tens of millions of products by price, newest, rating or colour is a search problem, not a database problem, and the production-grade answer is to serve it from a purpose-built search index fed by your system of record. This article walks through the full design for an Amazon-style store: the architecture, the data model, the Java 21 and Spring Boot code, the pagination trap that kills most implementations, and what you could pick instead of the JVM.

## TL;DR

- **Do not sort in your OLTP database.** `ORDER BY price` on a filtered, multi-million-row table does not survive Black Friday.
- **Use Elasticsearch or OpenSearch** as the read model. Sorting runs on columnar `doc_values`, which are built for exactly this.
- **Keep the database as the source of truth** and sync with a transactional outbox, CDC and Kafka. The index is a projection you can rebuild at any time.
- **Expose a whitelisted sort contract** (`sort=price_asc`), never raw field names. Add a unique tie-breaker to every sort.
- **Paginate with `search_after` and a point-in-time (PIT)**, not `from/size` offsets.
- **"Sort by colour" is really a filter or facet**, optionally with a ranking boost. We will see why this distinction matters.
- **Java 21 with virtual threads** is a strong fit for the API layer. Go, Rust and Kotlin are credible alternatives, and Typesense or Vespa may beat Elasticsearch for some teams.

## 1. Requirements: what "sortable" really has to mean

Before choosing technology, pin down what the feature must do and the load it must survive. The numbers below are planning assumptions for an Amazon-like store; replace them with your own traffic data.

### Functional requirements

| Sort option | Underlying field | Direction | Notes |
| --- | --- | --- | --- |
| Price: low to high / high to low | `price.amount` (numeric) | asc / desc | Sort on the effective (sale) price, in a single currency or normalised value |
| Newest first | `listedAt` (date) | desc | Use the listing date, not `updatedAt` |
| Customer rating | `ratingBayesian` (float) | desc | Never sort on the raw average (see section 8) |
| Best selling | `salesRank30d` (long) | desc | Updated by a batch or stream job |
| Relevance (default) | `_score` plus boosts | desc | Used when a search text is present |
| Colour | `colors` (keyword) | n/a | A filter or facet, optionally a boost, not an ordering |

Users must also be able to **combine** sorting with filters (category, brand, price range, colour, rating) and with free-text search, and page through results consistently.

### Non-functional requirements

| Concern | Planning target | Why it matters |
| --- | --- | --- |
| Latency | p95 under 200 ms, p99 under 400 ms for a listing page | Search latency translates directly into conversion |
| Catalogue size | 10 to 100 million SKUs, growing | Rules out full scans and in-memory sorting |
| Read/write ratio | Reads dominate by orders of magnitude | Justifies a separate, read-optimised store |
| Freshness | Price and stock changes visible in seconds, not hours | Stale prices cause trust and legal problems |
| Availability | 99.95% or better for browse | Browse is the front door of revenue |
| Consistency | Eventual for the listing; strong at checkout | The cart re-validates the price from the source of truth |
| Stability | Deterministic order, no duplicates or gaps while paging | Users notice when products repeat between pages |

## 2. Why `ORDER BY` in your OLTP database breaks down

A relational database can sort a small, well-indexed table, but a catalogue with free combinations of filters and sorts defeats it for four structural reasons. Consider the query a shopper triggers by clicking "Shoes, Red, 4 stars and up, sort by price":

```sql
SELECT p.*
FROM   product p
JOIN   product_color pc ON pc.product_id = p.id
WHERE  p.category_id = 42
  AND  pc.color = 'RED'
  AND  p.rating >= 4.0
ORDER  BY p.price ASC
LIMIT  20 OFFSET 4000;
```

1. **Index explosion.** Every useful (filter, sort) pair needs its own composite index: `(category, price)`, `(category, listed_at)`, `(category, brand, price)` and so on. Each index slows writes and consumes memory, and you still cannot cover every combination a shopper can pick.
2. **Multi-valued attributes need joins.** Colour, size and material are many-to-many. Joining and de-duplicating before sorting forces temporary tables and filesorts.
3. **`OFFSET` is linear.** Page 200 means the database reads and discards 4,000 rows to return 20. Latency grows with page depth, and concurrent deep scrolls (often bots) hammer the primary.
4. **Shared resource contention.** Browse traffic competes for buffer cache, CPU and connections with checkout and inventory writes, the part of the system that must never slow down.

Caching helps for hot queries, but the long tail of filter combinations produces a low hit rate. The scalable fix is to move reads to a **read model** that is designed for filtering, faceting and sorting, which is what a search engine is.

## 3. Target architecture: separate the read path from the write path

The design splits the system into a synchronous read path that never touches the database and an asynchronous write path that keeps the search index fresh. This is the CQRS pattern (Command Query Responsibility Segregation) applied to a catalogue: writes go to the system of record, reads go to a model shaped for queries.

&#91;embedded content: read path and write path · 9 components\]

A shopper's request passes the gateway, checks Redis for a cached first page, and otherwise queries the search index. Product changes flow from the database through change data capture (CDC) and Kafka into that same index.

### How the pieces fit

1. **PostgreSQL is the source of truth.** The catalog service writes the product and an `outbox` row in one transaction, so a change can never be saved without being published.
2. **CDC and Kafka decouple writers from readers.** A connector such as Debezium reads the database log and publishes events to a Kafka topic. Kafka keeps them ordered per product key and replayable, which is what lets you rebuild the index from scratch.
3. **The indexer builds search documents.** It consumes events, denormalises them (price, ratings, colours, derived sort fields) and performs idempotent bulk upserts.
4. **The search index serves every listing query.** Sorting, filtering and faceting run here, on columnar data, in a single round trip.
5. **The Search Service is a thin, defensive API.** It validates input, applies the sort whitelist, builds the query and fronts the cluster with a cache.
6. **The gateway protects the edge.** Authentication, rate limits and a CDN absorb abuse and repeated traffic before it reaches your services.

### Design principles behind the diagram

- **The index is disposable.** Because it is a projection, a corrupted or re-modelled index is fixed by replaying the log, not by a risky data migration.
- **Eventual consistency is acceptable for browsing.** A price may lag by a few seconds, so the cart and checkout always re-read price and stock from the source of truth.
- **Each store does what it is best at.** The database guarantees transactions; the search engine guarantees fast filtered sorting.

## 4. Choosing the sort engine

Elasticsearch or OpenSearch is the default choice for catalogue sorting because it combines filters, facets, full-text relevance and columnar sorting in one distributed engine, with a very large Java ecosystem. The table compares the realistic options.

| Engine | Sorting and facets | Horizontal scale | Operational cost | Best fit |
| --- | --- | --- | --- | --- |
| PostgreSQL (btree, GIN, partial indexes) | Good for few fixed sorts; poor for free filter combinations | Vertical, read replicas | Low | Small catalogues, MVPs |
| **Elasticsearch / OpenSearch** | Excellent: `doc_values` sorting, aggregations, function scoring | Sharding plus replicas, mature | Medium to high | Large catalogues with rich filters (recommended) |
| Apache Solr | Excellent, similar capability | Mature (SolrCloud) | Medium | Teams already on Solr |
| Typesense / Meilisearch | Very good for typical storefront needs, typo-tolerant | Typesense: in-memory, replicated; Meilisearch: mostly single node | Low | Small to mid catalogues needing speed and simplicity |
| Vespa | Excellent, with strong ranking and ML in the serving path | Very high, built for it | High | Large teams needing custom ranking at scale |
| MongoDB Atlas Search | Good, Lucene-based | Follows Atlas | Low if already on Atlas | Teams standardised on MongoDB |

Two practical notes shape the rest of this article:

- **Elasticsearch and OpenSearch share a heritage** but their APIs and licences have diverged. The patterns here apply to both; check the client library you pick matches your cluster.
- **Why sorting is fast in a search engine.** Each indexed field stores a column-oriented copy called `doc_values`, laid out on disk per segment. Sorting reads a compact column and a priority queue, rather than materialising and re-ordering rows.

## 5. Designing the index: model for the queries, not the tables

The index is a denormalised, query-shaped copy of your catalogue: one document per product that already contains everything needed to filter and sort, with no joins at query time. Here is a mapping that supports every sort option from section 1.

```json
PUT /products_v1
{
  "settings": { "number_of_shards": 6, "number_of_replicas": 1, "refresh_interval": "5s" },
  "mappings": {
    "dynamic": "strict",
    "properties": {
      "productId":     { "type": "keyword" },
      "title":         { "type": "text", "fields": { "raw": { "type": "keyword", "ignore_above": 256 } } },
      "brand":         { "type": "keyword" },
      "categoryPath":  { "type": "keyword" },
      "colors":        { "type": "keyword" },
      "sizes":         { "type": "keyword" },
      "priceMinor":    { "type": "long" },
      "currency":      { "type": "keyword" },
      "listedAt":      { "type": "date" },
      "ratingAvg":     { "type": "float" },
      "ratingCount":   { "type": "integer" },
      "ratingBayes":   { "type": "float" },
      "salesRank30d":  { "type": "long" },
      "inStock":       { "type": "boolean" },
      "variants": {
        "type": "nested",
        "properties": {
          "sku":        { "type": "keyword" },
          "color":      { "type": "keyword" },
          "size":       { "type": "keyword" },
          "priceMinor": { "type": "long" },
          "inStock":    { "type": "boolean" }
        }
      }
    }
  }
}
```

### Modelling decisions that matter

- **Store money as an integer in minor units** (`priceMinor`, for example paise or cents), plus a currency code. Floating point prices cause rounding bugs and unstable ordering.
- **Use `keyword` for colour, brand and size.** Keywords are exact-match, aggregatable and sortable. Use `text` only for fields users search by words.
- **Precompute derived sort fields** such as `ratingBayes` and `salesRank30d` at indexing time. Computing them with scripts at query time is slow and cannot use the columnar fast path.
- **Set `dynamic: strict`** so a stray field from an upstream change fails loudly instead of silently bloating the mapping.
- **Version the index name** (`products_v1`) and serve traffic through an alias. This is what makes zero-downtime reindexing possible (section 6).

### Products versus variants

A "red size 9 sneaker" is a variant of a product, and variants often differ in price and stock. You have two models:

| Model | How it works | Pros | Cons |
| --- | --- | --- | --- |
| Product document with `nested` variants | One document per product, variants as nested objects | Natural for listing pages: one card per product | Nested queries cost more; sorting by the cheapest in-stock variant needs a precomputed `priceMinor` |
| One document per variant, grouped at query time | Each SKU is a document, results collapsed on `productId` | Exact filter semantics per variant | Larger index; collapse adds cost and complicates pagination and counts |

For most storefronts, **product-level documents with a precomputed representative price** (the lowest in-stock variant, or the variant that matches the active colour filter) is the pragmatic choice. Use nested queries only when the filter must match several attributes of the *same* variant, such as colour red *and* size 9 in stock.

## 6. Keeping the index fresh: outbox, CDC and zero-downtime reindexing

The index is only useful if it tracks the database reliably, and the safest way to guarantee that is the transactional outbox pattern combined with CDC. Writing to the database and then publishing to Kafka as two separate steps (a dual write) will eventually lose or reorder an event; writing both in one database transaction cannot.

### Step 1: write the event in the same transaction

```sql
CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,           -- 'product'
    aggregate_id   TEXT        NOT NULL,           -- productId, used as the Kafka key
    event_type     TEXT        NOT NULL,           -- 'ProductCreated', 'PriceChanged', ...
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

```java
@Transactional
public void changePrice(String productId, long newPriceMinor) {
    Product p = products.findById(productId).orElseThrow();
    p.changePrice(newPriceMinor);                       // increments p.version
    outbox.save(OutboxEvent.of("product", productId, "PriceChanged",
                               mapper.valueToTree(ProductEvent.from(p))));
}   // product row and outbox row commit together, or neither does
```

A CDC connector (Debezium with its outbox event router) tails the database log and publishes each outbox row to Kafka, keyed by `productId`. Keying by product keeps all events for one product in order, which matters because the index must never apply an older price after a newer one.

### Step 2: an idempotent, batching indexer

```java
@KafkaListener(topics = "catalog.product-events", groupId = "product-indexer", concurrency = "6",
               containerFactory = "batchFactory")
public void onBatch(List<ConsumerRecord<String, ProductEvent>> records, Acknowledgment ack)
        throws IOException {

    var bulk = new BulkRequest.Builder();
    for (var r : records) {
        ProductEvent e = r.value();
        ProductDocument doc = mapper.toDocument(e);       // denormalise: price, colours, ratingBayes, ...
        bulk.operations(op -> op.index(i -> i
                .index("products-write").id(doc.productId())
                .document(doc)
                .versionType(VersionType.External)         // a stale event can never overwrite a newer one
                .version(e.version())));
    }
    BulkResponse resp = es.bulk(bulk.build());
    failures.handle(resp, records);                        // 409 version conflicts are expected and ignored
    ack.acknowledge();                                     // commit offsets only after Elasticsearch accepts
}
```

The properties that make this safe:

- **External versioning** uses the database row version, so replays and duplicates are harmless: an event with an older version is rejected with a conflict, which you ignore.
- **Manual acknowledgement** after the bulk call gives at-least-once delivery; idempotence turns that into effectively-once results.
- **A dead-letter topic** receives events that fail for non-transient reasons (for example a mapping violation), so one poison message never blocks the partition.
- **Deletes** are events too: a `ProductDelisted` event becomes a delete operation, or flips `inStock` to false if you want to keep the document.
- **Backpressure** comes from batch size and consumer concurrency; watch consumer lag as a first-class alert.

### Step 3: rebuild without downtime

Sooner or later you will change a mapping, add a sort field or fix a bug in the document builder, and Elasticsearch cannot change most mappings in place. The answer is to treat each index as immutable and move traffic by alias, as the diagram shows.

&#91;embedded content: zero-downtime reindex · 5 steps and an alias\]

The API only ever queries the `products-read` alias, so the new index can be built and verified while the old one keeps serving shoppers. The cut-over is a single atomic call:

```json
POST _aliases
{
  "actions": [
    { "remove": { "index": "products_v1", "alias": "products-read" } },
    { "add":    { "index": "products_v2", "alias": "products-read" } }
  ]
}
```

Rollback is the same call in reverse, which is why you keep `products_v1` for a grace period. Two practical notes: run a second consumer group to fill `products_v2` while the primary group keeps feeding `products_v1`, so no update is lost during the backfill; and use a log-compacted topic (or a database snapshot job) as the backfill source, since a normal topic may have expired its oldest events.

## 7. Implementation with Java 21 and Spring Boot

The API layer should be a thin, defensive translation from a small public contract to an Elasticsearch query, and Java 21 gives you the right tools for it: records for immutable DTOs, pattern matching for clean switches, and virtual threads for cheap blocking I/O. The code targets Spring Boot 3.2 or later and the Elasticsearch Java API client (8.13 or later); package names are abbreviated.

### Dependencies and configuration

```xml
<dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-elasticsearch</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
  <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-spring-boot3</artifactId></dependency>
  <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
</dependencies>
```

```yaml
spring:
  threads:
    virtual:
      enabled: true          # Tomcat and @Async run on virtual threads (Java 21)
  elasticsearch:
    uris: https://search.internal:9200
    connection-timeout: 1s
    socket-timeout: 2s       # fail fast: a slow search is worse than a degraded one
catalog:
  search:
    index-alias: products-read
    max-page-size: 100
    pit-keep-alive: 2m
```

### The public contract

Expose a stable, business-level vocabulary and keep field names private. A request looks like this:

```
GET /api/v1/products?category=footwear/sneakers&color=RED&color=BLUE
    &minPrice=100000&maxPrice=500000&minRating=4
    &sort=price_asc&size=24&cursor=eyJwaXQiOi...
```

The response returns the items, an opaque `nextCursor`, and facet counts. Never accept a raw field name or script in `sort`: that is an injection and denial-of-service vector, because sorting on an unmapped or text field forces expensive fielddata loading.

### Request model and sort whitelist

```java
public enum SortOption {
    RELEVANCE   ("relevance",    null,           null),
    PRICE_ASC   ("price_asc",    "priceMinor",   SortOrder.Asc),
    PRICE_DESC  ("price_desc",   "priceMinor",   SortOrder.Desc),
    NEWEST      ("newest",       "listedAt",     SortOrder.Desc),
    TOP_RATED   ("top_rated",    "ratingBayes",  SortOrder.Desc),
    BEST_SELLING("best_selling", "salesRank30d", SortOrder.Asc);   // rank 1 is best

    private final String code;
    private final String field;
    private final SortOrder order;

    SortOption(String code, String field, SortOrder order) {
        this.code = code; this.field = field; this.order = order;
    }

    public static SortOption fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new InvalidSortException(code));   // mapped to HTTP 400
    }

    /** Primary sort plus a unique tie-breaker so the order is total and stable. */
    public List<SortOptions> toEsSort() {
        var sorts = new ArrayList<SortOptions>(2);
        if (field == null) {
            sorts.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
        } else {
            sorts.add(SortOptions.of(s -> s.field(f -> f
                    .field(field).order(order).missing(FieldValue.of("_last")))));
        }
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("productId").order(SortOrder.Asc))));
        return sorts;
    }
}

public record ProductSearchRequest(
        String q, String category, List<String> colors, List<String> brands,
        Long minPriceMinor, Long maxPriceMinor, Double minRating,
        SortOption sort, int size, String cursor) {

    public ProductSearchRequest {                       // compact constructor = validation + defaults
        colors = colors == null ? List.of() : List.copyOf(colors);
        brands = brands == null ? List.of() : List.copyOf(brands);
        size   = Math.clamp(size, 1, 100);               // Java 21: Math.clamp
        if (sort == null) sort = (q == null || q.isBlank()) ? SortOption.NEWEST : SortOption.RELEVANCE;
    }
}
```

Three details here are worth calling out. The enum is the **whitelist**: only these six orderings can ever reach the cluster. Every sort ends with `productId` as a **unique tie-breaker**; without it, two products with the same price can swap places between requests and appear twice or vanish across pages. And the tie-breaker uses a `keyword` field, not `_id`, because sorting on `_id` loads fielddata into heap.

### The search service

The service builds the filter, applies the whitelisted sort, and paginates with `search_after` inside a point-in-time (PIT). A PIT freezes a consistent view of the index, so products do not shift between pages while the user scrolls.

```java
@Service
public class ProductSearchService {

    private static final String[] SUMMARY_FIELDS =
            {"productId", "title", "brand", "priceMinor", "currency", "ratingAvg", "ratingCount", "imageUrl"};

    private final ElasticsearchClient es;
    private final CursorCodec cursors;
    private final String alias;
    private final Time keepAlive;

    public ProductSearchService(ElasticsearchClient es, CursorCodec cursors, SearchProperties props) {
        this.es = es;
        this.cursors = cursors;
        this.alias = props.indexAlias();
        this.keepAlive = Time.of(t -> t.time(props.pitKeepAlive()));
    }

    public SearchPage<ProductSummary> search(ProductSearchRequest req) throws IOException {
        Cursor cursor = cursors.decode(req.cursor());                    // null on the first page
        String pitId  = cursor != null ? cursor.pitId() : openPit();

        var builder = new SearchRequest.Builder()
                .pit(p -> p.id(pitId).keepAlive(keepAlive))
                .query(buildQuery(req))
                .sort(req.sort().toEsSort())
                .size(req.size() + 1)                                    // one extra row tells us if a next page exists
                .trackTotalHits(t -> t.enabled(false))                   // exact totals are costly; approximate them separately
                .source(s -> s.filter(f -> f.includes(List.of(SUMMARY_FIELDS))));

        if (cursor != null) {
            builder.searchAfter(cursor.sortValues().stream().map(ProductSearchService::toFieldValue).toList());
        }

        SearchResponse<ProductSummary> resp = es.search(builder.build(), ProductSummary.class);

        List<Hit<ProductSummary>> hits = resp.hits().hits();
        boolean hasNext = hits.size() > req.size();
        List<Hit<ProductSummary>> page = hasNext ? hits.subList(0, req.size()) : hits;

        String next = null;
        if (hasNext) {
            Hit<ProductSummary> last = page.get(page.size() - 1);
            String pit = resp.pitId() != null ? resp.pitId() : pitId;
            next = cursors.encode(new Cursor(pit, last.sort().stream().map(ProductSearchService::toJava).toList()));
        }
        return new SearchPage<>(page.stream().map(Hit::source).toList(), next);
    }

    private String openPit() throws IOException {
        return es.openPointInTime(o -> o.index(alias).keepAlive(keepAlive)).id();
    }

    private Query buildQuery(ProductSearchRequest r) {
        var bool = new BoolQuery.Builder()
                .filter(f -> f.term(t -> t.field("inStock").value(true)));   // filters are cached and unscored

        if (r.q() != null && !r.q().isBlank()) {
            bool.must(m -> m.multiMatch(mm -> mm.query(r.q()).fields("title^3", "brand^2")));
        }
        if (r.category() != null) {                                          // categoryPath holds every ancestor
            bool.filter(f -> f.term(t -> t.field("categoryPath").value(r.category())));
        }
        if (!r.colors().isEmpty()) {
            bool.filter(f -> f.terms(t -> t.field("colors")
                    .terms(v -> v.value(r.colors().stream().map(FieldValue::of).toList()))));
        }
        if (!r.brands().isEmpty()) {
            bool.filter(f -> f.terms(t -> t.field("brand")
                    .terms(v -> v.value(r.brands().stream().map(FieldValue::of).toList()))));
        }
        if (r.minPriceMinor() != null || r.maxPriceMinor() != null) {
            bool.filter(f -> f.range(rg -> rg.number(n -> {
                n.field("priceMinor");
                if (r.minPriceMinor() != null) n.gte(r.minPriceMinor().doubleValue());
                if (r.maxPriceMinor() != null) n.lte(r.maxPriceMinor().doubleValue());
                return n;
            })));
        }
        if (r.minRating() != null) {
            bool.filter(f -> f.range(rg -> rg.number(n -> n.field("ratingAvg").gte(r.minRating()))));
        }
        return bool.build()._toQuery();
    }

    // Java 21 pattern matching for switch keeps cursor (de)serialisation tidy and type-safe.
    private static FieldValue toFieldValue(Object v) {
        return switch (v) {
            case Long l    -> FieldValue.of(l);
            case Integer i -> FieldValue.of(i.longValue());
            case Double d  -> FieldValue.of(d);
            case Boolean b -> FieldValue.of(b);
            case String s  -> FieldValue.of(s);
            default -> throw new InvalidCursorException();
        };
    }

    private static Object toJava(FieldValue v) {
        return switch (v._kind()) {
            case Long    -> v.longValue();
            case Double  -> v.doubleValue();
            case Boolean -> v.booleanValue();
            case String  -> v.stringValue();
            default      -> throw new IllegalStateException("Unsupported sort value: " + v);
        };
    }
}
```

### Cursor and controller

The cursor is an opaque, URL-safe Base64 string carrying the PIT id and the last row's sort values. Sign it (HMAC) so clients cannot forge sort values or reach other users' PITs.

```java
public record Cursor(String pitId, List<Object> sortValues) {}

@Component
public class CursorCodec {
    private final ObjectMapper mapper;
    private final Mac mac;                       // HmacSHA256 initialised from a secret

    public String encode(Cursor c) {
        byte[] body = mapper.writeValueAsBytes(c);
        byte[] sig  = mac.doFinal(body);
        return b64(body) + "." + b64(sig);
    }

    public Cursor decode(String token) {
        if (token == null || token.isBlank()) return null;
        // split, verify signature in constant time, then mapper.readValue(body, Cursor.class)
        // any failure -> InvalidCursorException -> HTTP 400
    }
}

@RestController
@RequestMapping("/api/v1/products")
class ProductSearchController {

    private final ProductSearchService service;

    ProductSearchController(ProductSearchService service) { this.service = service; }

    @GetMapping
    SearchPage<ProductSummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(name = "color", required = false) List<String> colors,
            @RequestParam(name = "brand", required = false) List<String> brands,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false) String cursor) throws IOException {

        SortOption option = sort == null ? null : SortOption.fromCode(sort);
        return service.search(new ProductSearchRequest(
                q, category, colors, brands, minPrice, maxPrice, minRating, option, size, cursor));
    }
}
```

**Why virtual threads help here.** Each request spends almost all of its time waiting on Elasticsearch and Redis. With `spring.threads.virtual.enabled=true`, every request runs on a virtual thread, so blocking calls no longer pin an expensive platform thread and you can hold thousands of concurrent in-flight searches without reactive code. Keep downstream concurrency bounded (connection pools, bulkheads), because virtual threads make it easy to overload a dependency.

## 8. Sorting strategies in depth

Each sort option has one trap that quietly ruins results if you ignore it, so treat them individually. The table gives the summary; the subsections explain the non-obvious ones.

| Sort | Field | Precompute? | The trap to avoid |
| --- | --- | --- | --- |
| Price | `priceMinor` | Yes: effective price of the representative variant | Sorting on list price when a sale price applies; mixed currencies |
| Newest | `listedAt` | No | Using `updatedAt`, which bumps every time stock or price changes |
| Top rated | `ratingBayes` | Yes | Ranking a 5.0 from two reviews above a 4.6 from three thousand |
| Best selling | `salesRank30d` | Yes (stream or batch job) | Recomputing rank synchronously on every order |
| Relevance | `_score` | Partly (boost fields) | Letting pure text score ignore stock, margin or quality |
| Colour | `colors` | n/a | Treating a category as an ordering |

### Rating: sort on a Bayesian average, not the raw mean

A raw average rewards products with very few reviews, so a brand-new item with two five-star votes beats a proven favourite. The standard fix is a weighted (Bayesian) average that pulls low-volume products toward the global mean until they earn trust:

```latex
score = \frac{v}{v + m} \cdot R + \frac{m}{v + m} \cdot C
```

Here `R` is the product's average rating, `v` its number of ratings, `C` the catalogue-wide mean rating, and `m` the number of ratings you require before trusting a product's own average. With `C = 4.0` and `m = 50`, a product rated 5.0 by 2 customers scores about 4.04, while one rated 4.6 by 3,000 customers scores about 4.59, which is the order shoppers expect. Compute this in the indexer or a periodic job, store it as `ratingBayes`, and keep `ratingAvg` for display and the "4 stars and up" filter.

### Price and recency

- **Price.** Sort on the price the customer will actually pay. If a product has variants with different prices, store the lowest in-stock price (or the price of the variant matching the active colour or size filter) in `priceMinor`. Normalise currencies at ingestion, not at query time.
- **Newest.** Index an immutable `listedAt` timestamp. Sorting on a field that changes constantly makes the order jump between page loads and causes duplicate or missing items while paging.
- **Missing values.** Always set `missing: _last` (as in `SortOption`) so products without a rating or price fall to the end instead of the top.

### Best selling and other fast-moving signals

Popularity changes constantly, but you should not reindex whole documents for it. Let a stream job (Kafka Streams or Flink) or an hourly batch compute `salesRank30d` and push **partial updates** through the bulk API, touching one field per document. This keeps indexing load small and the signal fresh enough, without putting analytics on the hot write path.

### Colour: a filter and a facet, not a sort

Colour has no natural order, so "sort by colour" really means one of three things, each with its own implementation:

1. **"Show me red ones"** is a `terms` filter on `colors` (as in the service above).
2. **"Show colour options with counts"** is a facet, built with an aggregation.
3. **"Prefer red but keep everything"** is a ranking boost: a `should` clause on `colors: RED` with a boost, combined with the chosen sort only under relevance.

For facets, apply the user's colour selection in `post_filter` and aggregate beforehand, so the colour facet still shows the other colours' counts:

```json
{
  "query": { "bool": { "filter": [ { "term": { "categoryPath": "footwear/sneakers" } } ] } },
  "post_filter": { "terms": { "colors": [ "RED" ] } },
  "aggs": {
    "colors": { "terms": { "field": "colors", "size": 20 } },
    "brands": { "terms": { "field": "brand", "size": 20 } },
    "price":  { "stats": { "field": "priceMinor" } }
  },
  "sort": [ { "priceMinor": "asc" }, { "productId": "asc" } ]
}
```

### Combining criteria

Shoppers often want "highest rated, then cheapest". Support this by accepting up to two whitelisted keys (`sort=top_rated,price_asc`) and mapping them in order, always followed by the unique tie-breaker. Do not allow arbitrary chains: every extra sort key adds comparison cost and cache fragmentation.

### Pagination: choose the right tool

Deep `from/size` pagination is the most common production mistake in listing APIs. Elasticsearch caps `from + size` at 10,000 by default for good reason: each shard must build and ship `from + size` entries to the coordinating node for every request.

| Approach | Cost at depth | Stable while paging | Jump to page N | Use for |
| --- | --- | --- | --- | --- |
| `from` + `size` | Grows with offset; capped at 10,000 | No | Yes | Numbered pages in the first few pages only |
| Scroll | Constant | Snapshot | No | Exports and batch jobs, not shopper traffic |
| `search_after` + PIT | Constant | Yes | No | Infinite scroll and "next page" (recommended) |

A pragmatic hybrid: allow numbered pages with `from/size` for the first handful of pages, then switch to cursor-based `search_after` beyond that. Also cap the maximum depth a client may reach; very deep scrolling is almost always a bot.

## 9. Performance: caching, sharding and query tuning

Most of the speed comes from four decisions: cache the first page of popular listings, size shards sensibly, keep sorts on columnar fields, and never ask the engine for more than the page needs.

### Layered caching

| Layer | What is cached | Typical TTL | Watch out for |
| --- | --- | --- | --- |
| CDN or edge | First page of anonymous category listings | Seconds to a minute | Personalised content and price changes |
| Application cache (Redis) | Serialised first page keyed by the normalised query | Seconds to minutes | Cache key fragmentation, stampedes |
| Elasticsearch filter and request caches | Filter bitsets and aggregation results per shard | Automatic, invalidated on refresh | Frequent refreshes reduce hit rate |
| Client | Recently viewed pages in the browser | Session | Stale prices; always re-check at add-to-cart |

Cache **only the first page** (`cursor == null`). Later pages depend on a PIT that expires, so they must hit the cluster. Normalise the key so equivalent requests collide: sort and de-duplicate the colour list, lower-case the category, and drop default values.

```java
public record ProductSearchRequest(/* ... fields as before ... */) {

    public String cacheKey() {
        return String.join("|",
                nullToEmpty(q), nullToEmpty(category),
                String.join(",", new TreeSet<>(colors)),
                String.join(",", new TreeSet<>(brands)),
                String.valueOf(minPriceMinor), String.valueOf(maxPriceMinor), String.valueOf(minRating),
                sort.name(), String.valueOf(size));
    }
}

@Cacheable(cacheNames = "plp-first-page", key = "#req.cacheKey()", sync = true,
           condition = "#req.cursor() == null")
public SearchPage<ProductSummary> search(ProductSearchRequest req) throws IOException { /* ... */ }
```

`sync = true` makes concurrent misses for the same key wait for a single load inside one instance, which protects Elasticsearch from a stampede when a hot cache entry expires. Add jitter to TTLs so entries do not all expire together.

### Cluster and index tuning

- **Shard size.** Aim for shards in the tens of gigabytes (Elastic's guidance is roughly 10 to 50 GB). Too many small shards waste heap and add coordination cost; a few huge shards slow recovery. Start with a shard count you can reason about, and use the reindex flow in section 6 to change it later.
- **Memory.** Give the JVM about half of the node's RAM, no more than the compressed-pointer limit (around 30 GB), and leave the rest to the operating system page cache, where `doc_values` live. Sorting speed depends on that cache being warm.
- **Refresh interval.** The default one-second refresh favours freshness at the cost of indexing throughput and cache invalidation. For a catalogue, `5s` to `30s` is usually acceptable; bulk loads can disable it temporarily.
- **Index sorting.** If one sort dominates (for example newest first), `index.sort.field` can store segments pre-ordered, allowing queries on that order to terminate early. It costs indexing time and only helps that order, so use it deliberately.
- **Return less.** Use `_source` filtering (as in the service), avoid fetching large descriptions on listing pages, and disable exact `track_total_hits`. Show "10,000+ results" or compute exact counts from the facet aggregation if the product truly needs them.
- **Avoid script sorting.** Painless scripts in `sort` run per document and bypass the fast columnar path. If you need a computed order, precompute it into a field.
- **Separate roles.** Run dedicated master, data and coordinating nodes in production, and route the listing API through coordinating nodes so heavy aggregations do not starve indexing.

## 10. Resilience, observability and testing

A listing page should degrade gracefully when search is slow or down, because a stale or simplified product list still earns revenue while an error page does not. Build three safety nets: fail fast, fall back, and see everything.

### Fail fast and fall back

```java
@CircuitBreaker(name = "es-search", fallbackMethod = "degraded")
@Bulkhead(name = "es-search", type = Bulkhead.Type.SEMAPHORE)
public SearchPage<ProductSummary> search(ProductSearchRequest req) throws IOException { /* ... */ }

SearchPage<ProductSummary> degraded(ProductSearchRequest req, Throwable cause) {
    log.warn("search degraded: {}", cause.toString());
    return staleCache.find(req.cacheKey())               // 1. last known good page, even if expired
            .map(SearchPage::markDegraded)
            .orElseGet(() -> popularItems.forCategory(req.category()));   // 2. precomputed popular list
}
```

```yaml
resilience4j:
  circuitbreaker:
    instances:
      es-search:
        sliding-window-size: 50
        failure-rate-threshold: 50
        slow-call-duration-threshold: 1s
        slow-call-rate-threshold: 60
        wait-duration-in-open-state: 10s
  bulkhead:
    instances:
      es-search:
        max-concurrent-calls: 200      # protects the cluster even with virtual threads
```

Add per-client **rate limiting** at the gateway, a maximum page depth, and query-size limits (number of colours, brands and sort keys) so a single abusive client cannot create expensive queries.

### Observability

Instrument the search path with Micrometer and tag by the dimensions you will debug with later:

```java
Timer.builder("catalog.search")
     .tag("sort", req.sort().name())
     .tag("hasQuery", String.valueOf(req.q() != null))
     .publishPercentileHistogram()
     .register(registry)
     .record(() -> /* execute search */);
```

- **Metrics:** request rate, p50/p95/p99 latency per sort option, error rate, cache hit ratio, circuit-breaker state, and degraded-response count.
- **Cluster signals:** search thread pool queue and rejections, JVM heap, GC pauses, shard sizes, indexing lag.
- **Pipeline health:** Kafka consumer lag and the age of the newest indexed document. Alert when *index freshness* exceeds your target, since the API will look healthy while serving stale prices.
- **Tracing:** propagate trace ids through the gateway, API and Elasticsearch calls (OpenTelemetry), and enable the slow log on the index to catch expensive queries.

### Testing what matters

The highest-value test walks every page of a result set and proves the order is total and stable. Run it against a real Elasticsearch using Testcontainers rather than mocks:

```java
@Testcontainers
@SpringBootTest
class SortingIT {

    @Container
    static ElasticsearchContainer es = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.15.0")
            .withEnv("xpack.security.enabled", "false");

    @Autowired ProductSearchService service;

    @ParameterizedTest
    @EnumSource(value = SortOption.class, names = {"PRICE_ASC", "PRICE_DESC", "NEWEST", "TOP_RATED"})
    void paging_through_everything_has_no_duplicates_and_respects_order(SortOption sort) throws Exception {
        var seen = new LinkedHashSet<String>();
        String cursor = null;
        do {
            var page = service.search(new ProductSearchRequest(null, null, null, null, null, null, null, sort, 50, cursor));
            page.items().forEach(p -> assertTrue(seen.add(p.productId()), "duplicate " + p.productId()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(TOTAL_SEEDED_PRODUCTS, seen.size());
    }
}
```

Also cover: the sort whitelist rejects unknown values with HTTP 400, tampered cursors are rejected, missing ratings sort last, and a load test (k6 or Gatling) that replays real filter and sort mixes at peak concurrency, including deep pagination and a cold cache.

## 11. Is there a better stack? Alternatives to consider

Changing the API language will barely move your sorting performance, because the time goes into the search engine and the network; the choice of search engine and ranking model matters far more. In a typical request the API layer only translates parameters, calls the cluster and maps the response, so Java 21 with virtual threads is already efficient enough for most teams.

### API layer: language and framework

| Option | Strengths | Trade-offs | Choose it when |
| --- | --- | --- | --- |
| **Java 21 + Spring Boot** (this article) | Mature ecosystem, virtual threads, strong tooling, easy hiring in enterprises | Higher memory and slower start than native runtimes | Your team and platform are JVM-based (recommended default) |
| Spring Boot native image (GraalVM) | Fast start, lower memory, same code | Longer builds, reflection constraints | Many small pods, autoscaling spikes |
| Kotlin (Spring or Ktor) | Concise code, coroutines, full JVM interop | Another language to staff | You want less boilerplate without leaving the JVM |
| Go | Small footprint, quick start, simple concurrency | Fewer enterprise libraries, more hand-written plumbing | A thin, high-traffic search gateway or BFF |
| Rust (Axum) | Lowest overhead and tail latency | Highest development cost and hiring barrier | Extreme scale where per-request cost dominates |
| TypeScript (NestJS) | One language with the front end, quick iteration, good GraphQL fit | Single-threaded CPU limits; fewer guarantees | A BFF that aggregates several services for the UI |

### Search layer: where real improvements live

| Option | Where it can beat Elasticsearch | Where it falls short |
| --- | --- | --- |
| **OpenSearch** | Open-source licence and managed offerings with the same query model | Features and client libraries drift from Elasticsearch over time |
| **Typesense** | Simple operations, built-in typo tolerance, fast typical storefront queries | Memory-bound, smaller ecosystem for advanced analytics |
| **Meilisearch** | Excellent developer experience for small catalogues | Not designed for very large, multi-node deployments |
| **Vespa** | Real-time updates, first-class ranking with machine-learned models, grouping and sorting in one engine | Steeper learning curve, smaller community |
| **Managed services** (Elastic Cloud, Amazon OpenSearch Service, Algolia) | Removes cluster operations; faster time to market | Cost at scale, less control over internals |

### What I would pick

1. **Most teams building an Amazon-like store:** Java 21, Spring Boot, and Elasticsearch or OpenSearch, exactly as designed above.
2. **Small team, moderate catalogue, speed to market:** the same API with Typesense or a managed search service, to reduce operational work.
3. **Personalised ranking is the product:** evaluate Vespa, because ranking and serving live together and models can run close to the data.
4. **Large fleet of small pods:** keep Spring Boot but compile to a native image, or put a Go gateway in front for the hot read path.

Whichever you choose, the architecture stays the same: a system of record, an event pipeline, a query-shaped index, and a thin API with a strict contract. That is the part worth getting right; the language is a replaceable detail. The code in this article carries over to later Java LTS releases without structural changes.

## 12. Production checklist and conclusion

Most sorting bugs in production come from a short list of avoidable mistakes, so review this checklist before launch.

- [ ] Sorting runs in a search index; the OLTP database serves no listing queries
- [ ] Public API accepts only whitelisted sort codes, never raw field names
- [ ] Every sort ends with a unique, keyword-based tie-breaker
- [ ] Money is stored as integer minor units with a currency code
- [ ] Rating sort uses a Bayesian score; missing values sort last
- [ ] Sort fields are immutable or slow-changing (`listedAt`, not `updatedAt`)
- [ ] Pagination uses `search_after` with PIT beyond the first few pages; maximum depth is capped
- [ ] Cursors are signed and expire
- [ ] Index is versioned behind an alias; a full reindex has been rehearsed
- [ ] Outbox plus CDC feeds the index; the indexer is idempotent and uses external versioning
- [ ] Dead-letter topic and replay procedure exist
- [ ] First pages are cached with normalised keys, jittered TTLs and stampede protection
- [ ] Circuit breaker, bulkhead, timeouts and a degraded fallback are in place
- [ ] Dashboards and alerts cover latency per sort option, cache hit ratio, consumer lag and index freshness
- [ ] Checkout re-validates price and stock against the source of truth
- [ ] Integration tests page through full result sets; a load test covers peak mix and deep pagination

### Conclusion

Production-grade product sorting is an architecture decision before it is a coding task. Move reads off the transactional database, give the search engine a denormalised and query-shaped index, keep it fresh with an event pipeline you can replay, and expose a deliberately small, whitelisted API that is stable under paging. Java 21 and Spring Boot make that API layer straightforward: records and pattern matching keep the code compact, and virtual threads let blocking calls scale without reactive complexity.

If you remember three things, make them these: sort on columnar fields with a unique tie-breaker, paginate with cursors instead of offsets, and treat colour and similar attributes as filters and facets rather than orderings. The rest is careful engineering and good observability.

If you found this useful, share it with the engineer on your team who is about to add another composite index to the `product` table.
