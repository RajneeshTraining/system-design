# Building Production-Grade Product Search and Sorting for E-Commerce with Java 21, Spring Boot, and OpenSearch

*Designing fast catalogue search, multi-criteria sorting, faceted
filters, pagination, and reliable index synchronization*

> **In this article:** We'll design an Amazon-style product discovery
> backend, explain why sorting is more than an `ORDER BY` clause,
> compare implementation choices, define an OpenSearch document model,
> explore query patterns, and outline a Java 21 + Spring Boot
> architecture that can evolve from a local starter project to a
> production system.

## Introduction: Sorting Is a Search Architecture Problem

Imagine a customer browsing an e-commerce catalogue. They search for
running shoes and want to:

-   Sort by price from low to high.
-   See the newest arrivals first.
-   Find products with the highest customer ratings.
-   Filter by colour, brand, size, price, and availability.
-   Combine several filters, such as black shoes under ₹5,000 with a
    rating of at least 4 stars.
-   Browse the results quickly, even when the catalogue contains
    millions of products.

The UI may show a dropdown called **Sort by**, but the backend must do
much more than reorder a small list. It must find eligible products,
apply business rules, rank or sort the results, calculate filter counts,
paginate consistently, and do all of this within a predictable latency
budget.

A simple implementation can work well for a small catalogue. At larger
scale, a poorly designed search endpoint can overload the transactional
database, become slow under combined filters, or return inconsistent
results while prices and inventory change.

The central architectural principle is:

**Use the transactional database as the source of truth, and use a
search-optimized read model for catalogue discovery when the workload
justifies it.**

For a Java-oriented enterprise backend, a strong default architecture
is:

-   Java 21 and Spring Boot for the API and business logic.
-   OpenSearch or Elasticsearch for catalogue search, filtering,
    sorting, and aggregations.
-   PostgreSQL or MySQL as the authoritative catalogue database.
-   Kafka plus a transactional outbox or CDC pipeline for asynchronous
    index updates.
-   Redis and a CDN where caching provides measurable benefits.
-   A cart/checkout validation path that verifies authoritative price
    and inventory before purchase.

This article explains the design step by step.

------------------------------------------------------------------------

## 1. Separate Filtering, Sorting, Relevance, and Faceting

These concepts are related, but they solve different problems.

### Filtering: Which products qualify?

Filtering removes products that do not meet the customer's constraints.

Examples:

-   Category is `RUNNING_SHOES`.
-   Colour includes `BLACK`.
-   Price is between ₹1,000 and ₹5,000.
-   Average rating is at least 4.0.
-   Product is visible and eligible for sale.

Filtering answers: **Which products are allowed in the result set?**

### Sorting: In what order should the qualifying products appear?

Sorting orders the products that passed the filters.

Examples:

-   `price ASC`
-   `price DESC`
-   `createdAt DESC`
-   `averageRating DESC`

Sorting answers: **Which eligible product appears first?**

### Relevance: How well does a product match a text query?

If the customer searches for "lightweight running shoes," the backend
may rank products based on how well their titles and descriptions match
the query.

Relevance is not the same as rating. A highly rated product that barely
matches the query may be less useful than a moderately rated product
that exactly matches the requested product type.

### Faceting: What other choices are available?

Facets display counts beside filters, for example:

-   Black (184)
-   Blue (126)
-   White (97)
-   Nike (85)
-   Adidas (72)

These counts help customers refine their search without restarting the
search process.

A mature catalogue search API often performs filtering, sorting, text
matching, and facet aggregation in a single search request.

------------------------------------------------------------------------

## 2. Choose the Right Search Architecture

There is no need to introduce a distributed search cluster into every
small application. Select the simplest architecture that satisfies the
workload, then evolve when measurements show a need.

### Option A: Relational database only

A relational database can support filtering and sorting with SQL:

``` sql
SELECT product_id, title, price, average_rating
FROM products
WHERE category_id = 'RUNNING_SHOES'
  AND price BETWEEN 1000 AND 5000
  AND average_rating >= 4.0
ORDER BY price ASC, product_id ASC
LIMIT 20 OFFSET 0;
```

This can be a very good solution when:

-   The catalogue and traffic are moderate.
-   Search is mostly structured filtering.
-   Full-text relevance and rich faceting are limited.
-   The team wants minimal operational complexity.
-   Proper indexes and query plans meet the latency target.

Useful database indexes depend on the actual predicates, cardinality,
and query patterns. A single index rarely optimizes every possible
combination of category, price, rating, stock, and sort order. Inspect
execution plans and benchmark representative queries.

### Option B: OpenSearch or Elasticsearch

A dedicated search engine becomes attractive when the product discovery
experience needs:

-   Full-text search and relevance scoring.
-   Many combinations of filters.
-   Faceted navigation and aggregations.
-   Numeric and date sorting at catalogue scale.
-   Search-specific ranking controls.
-   Distributed read capacity separate from transactional workloads.

This is the recommended default for a serious, search-heavy e-commerce
catalogue.

OpenSearch and Elasticsearch share many concepts, but their server
features, APIs, and client compatibility are not identical. Choose a
supported version and client combination and test it as a unit.

### Option C: Apache Solr

Solr is also a mature search platform with filtering, sorting, text
search, and faceting. It is a credible choice if your organization
already operates it or has requirements that favor its ecosystem.

### Option D: Managed search

A managed service can reduce the burden of operating search clusters. It
may offer convenient autocomplete, typo tolerance, and ranking tools.
Evaluate pricing, index limits, customization, data residency, latency,
and vendor dependency before choosing it.

### Architecture recommendation

For a Java enterprise team, start with **Spring Boot + OpenSearch + a
relational source of truth** when search complexity and traffic justify
the additional infrastructure. Do not switch programming languages
solely to improve sorting performance: the search engine and data model
usually matter more.

------------------------------------------------------------------------

## 3. Reference Architecture: CQRS for Catalogue Reads

A useful pattern is Command Query Responsibility Segregation (CQRS). It
separates the model used to change product data from the model used to
search it.

``` mermaid
flowchart TD
    C[Web or Mobile Client] --> G[API Gateway / CDN]
    G --> A[Spring Boot Product Search API]
    A --> S[(OpenSearch Catalogue Index)]

    P[Product Catalogue Service] --> DB[(PostgreSQL / MySQL)]
    P --> O[(Transactional Outbox)]
    O --> K[Kafka or CDC Pipeline]
    K --> I[Idempotent Indexing Consumer]
    I --> S

    A --> V[Cart / Checkout Validation]
    V --> DB
    V --> INV[Authoritative Inventory / Offer Service]
```

### Component responsibilities

**Product catalogue service**

-   Creates and updates product data.
-   Validates catalogue business rules.
-   Persists authoritative state.
-   Writes an outbox event in the same transaction as the product
    update.

**Search API**

-   Validates customer query parameters.
-   Applies visibility and access rules.
-   Converts customer sort options into an allowlisted search policy.
-   Queries OpenSearch.
-   Returns product cards, pagination metadata, and facets.

**OpenSearch**

-   Stores denormalized documents designed for searching.
-   Executes text queries, filters, sorts, and aggregations.
-   Provides a read-optimized view of catalogue data.

**Indexing pipeline**

-   Delivers product changes to the search index.
-   Handles retries, duplicate events, ordering, and failed messages.
-   Reconciles the index with the source of truth.

**Cart and checkout**

-   Revalidates price, stock, offer eligibility, and promotions.
-   Does not treat a search result as a purchase guarantee.

The search index is a **derived read model**, not the authority for
financial or inventory decisions.

------------------------------------------------------------------------

## 4. Design a Search-Oriented Product Document

Do not blindly copy a normalized relational schema into the search
index. Build a document containing the fields needed for search results,
filtering, sorting, and product cards.

Example document:

``` json
{
  "productId": "SKU-10001",
  "title": "Nike Men's Running Shoes",
  "description": "Lightweight running shoes for daily training",
  "categoryId": "RUNNING_SHOES",
  "brand": "Nike",
  "price": 3499.00,
  "currency": "INR",
  "discountPercentage": 20.0,
  "averageRating": 4.4,
  "ratingCount": 1250,
  "colors": ["BLACK", "BLUE"],
  "availableSizes": ["8", "9", "10"],
  "inStock": true,
  "createdAt": "2026-09-20T10:15:00Z",
  "updatedAt": "2026-10-15T14:30:00Z",
  "popularityScore": 850,
  "searchable": true
}
```

### Field design matters

-   **`productId`**: stable identifier and deterministic tie-breaker.
-   **`title` and `description`**: text fields for full-text search.
-   **`brand`, `categoryId`, `colors`, `availableSizes`**: exact-match
    fields.
-   **`price`**: numeric, never a formatted string such as `"₹3,499"`.
-   **`createdAt`**: date field with consistent timezone semantics.
-   **`averageRating` and `ratingCount`**: support rating filters and
    ranking policies.
-   **`inStock` and `searchable`**: support eligibility filters, while
    still requiring authoritative validation at purchase time.
-   **`popularityScore`**: optional ranking signal whose calculation
    should be documented and monitored.

### Product versus SKU versus offer

This is a critical modelling decision.

A product family may have multiple variants. A marketplace may also have
multiple sellers offering the same product at different prices and
delivery promises.

For example, a shoe family might have size 8 and size 9 variants, and
each size could be sold by several sellers. If price and inventory
belong to individual seller offers, one document per generic product may
not be enough.

Two common models are:

1.  **One document per sellable offer/SKU.** Search results can
    represent a specific purchasable variant or seller offer.
2.  **One document per product family with nested or separately modelled
    offers.** The query must ensure that filtering and sorting use the
    same qualifying offer.

If you filter on one seller's offer price but sort using another
seller's price, the result can be incorrect. Nested data or a carefully
designed offer index can prevent this class of bug.

------------------------------------------------------------------------

## 5. Example OpenSearch Index Mapping

The mapping defines how fields are indexed and how they can be queried
and sorted.

``` json
PUT products-v1
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "productId": { "type": "keyword" },
      "title": {
        "type": "text",
        "fields": {
          "keyword": {
            "type": "keyword",
            "ignore_above": 256
          }
        }
      },
      "description": { "type": "text" },
      "categoryId": { "type": "keyword" },
      "brand": { "type": "keyword" },
      "price": {
        "type": "scaled_float",
        "scaling_factor": 100
      },
      "averageRating": { "type": "half_float" },
      "ratingCount": { "type": "integer" },
      "colors": { "type": "keyword" },
      "availableSizes": { "type": "keyword" },
      "inStock": { "type": "boolean" },
      "createdAt": { "type": "date" },
      "updatedAt": { "type": "date" },
      "popularityScore": { "type": "long" },
      "searchable": { "type": "boolean" }
    }
  }
}
```

### Why use `keyword` and `text` differently?

A `text` field is analyzed into tokens and is useful for full-text
search. A `keyword` field is treated as an exact value and is
appropriate for filters, aggregations, and sorting.

The multi-field mapping for `title` allows both full-text search on
`title` and exact alphabetical sorting on `title.keyword`.

### Why use a numeric price?

Sorting formatted price strings can produce lexicographic behavior
rather than numeric order. Numeric mappings make range filters and
sorting behave as intended.

`scaled_float` can be appropriate for prices when its scaling and
precision match the currency model. For multi-currency catalogues, keep
currency explicit and do not compare raw amounts across currencies
without conversion or currency-specific constraints.

### What about shard counts?

The sample shard count is illustrative, not a universal recommendation.
Shard sizing depends on index size, query volume, indexing throughput,
replica requirements, and available resources. Benchmark realistic
workloads before choosing production values.

------------------------------------------------------------------------

## 6. Implement Multi-Criteria Filtering and Sorting

Suppose a customer requests:

-   Category: running shoes.
-   Colour: black.
-   Price: ₹1,000 to ₹5,000.
-   Rating: at least 4.0.
-   Sort: price ascending.
-   Page size: 20.

A single OpenSearch query can combine these conditions:

``` json
POST products-v1/_search
{
  "from": 0,
  "size": 20,
  "query": {
    "bool": {
      "filter": [
        { "term": { "categoryId": "RUNNING_SHOES" } },
        { "term": { "colors": "BLACK" } },
        {
          "range": {
            "price": {
              "gte": 1000,
              "lte": 5000
            }
          }
        },
        {
          "range": {
            "averageRating": {
              "gte": 4.0
            }
          }
        },
        { "term": { "searchable": true } }
      ]
    }
  },
  "sort": [
    { "price": "asc" },
    { "productId": "asc" }
  ]
}
```

### Why use filter context?

These conditions decide eligibility. They do not need to contribute to
text relevance scoring. Using the `bool.filter` context communicates
that intent and is suitable for structured constraints.

For a text search, add a relevance query such as `multi_match` in the
query context, and keep category, price, colour, and visibility
conditions in filter context.

### Always define a deterministic tie-breaker

Imagine 200 products have the same price. If you sort only by price, the
ordering among those products may not be stable. That can cause
duplicates or missing products when users navigate pages.

A stable unique tie-breaker such as `productId ASC` makes ordering
deterministic for a fixed index view.

### Missing values require a policy

Some products may have no rating or discount. Decide whether those
products should appear first or last in each sort mode. Configure
missing-value behavior explicitly instead of leaving it to accidental
defaults.

------------------------------------------------------------------------

## 7. Expose Business-Friendly Sort Options in Java

The public API should accept a controlled enum rather than arbitrary
index field names.

``` java
package com.example.catalogsearch.domain;

public enum ProductSort {
    RELEVANCE,
    PRICE_ASC,
    PRICE_DESC,
    NEWEST,
    RATING_DESC,
    POPULARITY_DESC,
    DISCOUNT_DESC
}
```

A business policy can map these options to trusted fields:

``` java
package com.example.catalogsearch.application;

import com.example.catalogsearch.domain.ProductSort;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SortPolicy {

    public List<SortField> resolve(ProductSort sort) {
        ProductSort effective =
                sort == null ? ProductSort.RELEVANCE : sort;

        return switch (effective) {
            case PRICE_ASC -> List.of(
                new SortField("price", Direction.ASC),
                new SortField("productId", Direction.ASC));

            case PRICE_DESC -> List.of(
                new SortField("price", Direction.DESC),
                new SortField("productId", Direction.ASC));

            case NEWEST -> List.of(
                new SortField("createdAt", Direction.DESC),
                new SortField("productId", Direction.ASC));

            case RATING_DESC -> List.of(
                new SortField("averageRating", Direction.DESC),
                new SortField("ratingCount", Direction.DESC),
                new SortField("productId", Direction.ASC));

            case POPULARITY_DESC -> List.of(
                new SortField("popularityScore", Direction.DESC),
                new SortField("productId", Direction.ASC));

            case DISCOUNT_DESC -> List.of(
                new SortField("discountPercentage", Direction.DESC),
                new SortField("productId", Direction.ASC));

            case RELEVANCE -> List.of(
                new SortField("_score", Direction.DESC),
                new SortField("productId", Direction.ASC));
        };
    }

    public enum Direction {
        ASC, DESC
    }

    public record SortField(String field, Direction direction) {}
}
```

This is a framework-independent policy layer. Your OpenSearch adapter
converts `SortField` values into the sort objects supported by the
specific client version you use.

A relevance sort must be paired with a meaningful scored query. A
`match_all` query does not create useful text relevance by itself.

For rating sorts, sorting by raw average rating can favor products with
very few reviews. A more advanced business policy can use a
confidence-adjusted rating or a ranking score that considers review
volume.

------------------------------------------------------------------------

## 8. Design the Spring Boot API

A useful endpoint is:

``` http
GET /api/v1/products
```

Example request:

``` http
GET /api/v1/products?q=running%20shoes
    &categoryId=RUNNING_SHOES
    &colors=BLACK
    &minPrice=1000
    &maxPrice=5000
    &minRating=4.0
    &sort=PRICE_ASC
    &page=0
    &size=20
```

A request DTO can validate the individual values:

``` java
package com.example.catalogsearch.api.dto;

import com.example.catalogsearch.domain.ProductSort;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.util.List;

public record ProductSearchRequest(
    String q,
    String categoryId,
    List<String> colors,
    List<String> brands,

    @DecimalMin("0.00")
    BigDecimal minPrice,

    @DecimalMin("0.00")
    BigDecimal maxPrice,

    @DecimalMin("0.0")
    @DecimalMax("5.0")
    Double minRating,

    Boolean inStock,
    ProductSort sort,

    @Min(0)
    @Max(10000)
    Integer page,

    @Min(1)
    @Max(100)
    Integer size
) {}
```

The application should additionally validate cross-field constraints
such as:

-   `minPrice <= maxPrice`.
-   Query length and allowed filter counts.
-   Supported colour, brand, and category values where required.
-   Maximum page size.
-   Whether the selected sort is allowed for the requested catalogue.
-   Region, tenant, and customer eligibility rules.

A controller can remain small:

``` java
package com.example.catalogsearch.api;

import com.example.catalogsearch.api.dto.ProductSearchRequest;
import com.example.catalogsearch.api.dto.ProductSearchResponse;
import com.example.catalogsearch.application.ProductSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class ProductSearchController {

    private final ProductSearchService searchService;

    public ProductSearchController(ProductSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ProductSearchResponse search(
            @Valid @ModelAttribute ProductSearchRequest request) {
        return searchService.search(request);
    }
}
```

This is an architectural example. It assumes the corresponding response
DTO and application service are implemented in the project.

### Recommended response shape

``` json
{
  "items": [
    {
      "productId": "SKU-10001",
      "title": "Nike Men's Running Shoes",
      "price": 3499.00,
      "currency": "INR",
      "averageRating": 4.4,
      "colors": ["BLACK", "BLUE"]
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 125,
  "facets": {
    "colors": [
      { "value": "BLACK", "count": 45 },
      { "value": "BLUE", "count": 30 }
    ]
  }
}
```

The counts are illustrative. The actual response contract should define
whether `totalElements` is exact or approximate and how facet counts
behave when a facet is already selected.

### Keep the search engine behind an interface

``` java
public interface ProductSearchRepository {
    ProductSearchResponse search(ProductSearchRequest request);
}
```

This keeps OpenSearch-specific request construction inside the
infrastructure layer. It also makes unit testing and future
search-engine migration easier.

------------------------------------------------------------------------

## 9. Faceted Navigation and Filter Counts

A good catalogue page often displays a list of available filter values
and counts.

Example aggregation:

``` json
{
  "aggs": {
    "colours": {
      "terms": {
        "field": "colors",
        "size": 20
      }
    },
    "brands": {
      "terms": {
        "field": "brand",
        "size": 50
      }
    }
  }
}
```

The counts are calculated over the documents matching the query, subject
to the aggregation structure.

### Disjunctive faceting

Suppose the customer selects Black. Should the colour panel show only
Black, or should it still show Blue and White as alternatives?

Many retail experiences use **disjunctive faceting**: the colour
aggregation applies all other filters but excludes the current colour
filter. This lets the customer see alternative colours available under
the selected category, price, and rating constraints.

That behavior needs to be designed explicitly using filter aggregations
or a suitable query structure. It should not be left to accidental query
composition.

Also be careful about counting semantics. If the index contains one
document per seller offer, the aggregation may count offers rather than
unique product families. The UI and API should clearly define which
count is being shown.

------------------------------------------------------------------------

## 10. Pagination: Offset versus `search_after`

Pagination has significant performance and correctness implications.

### Offset pagination

``` json
{
  "from": 0,
  "size": 20
}
```

This is simple and supports page numbers. It works well for the first
few pages, but deep offsets can require the search engine to collect and
discard many earlier hits.

### Cursor pagination with `search_after`

For deep browsing, use `search_after` with a deterministic sort:

``` json
{
  "size": 20,
  "sort": [
    { "price": "asc" },
    { "productId": "asc" }
  ],
  "search_after": [3499.0, "SKU-10001"]
}
```

The cursor values must come from the final hit of the previous page and
include all sort values.

### Point in Time (PIT)

If the index changes between requests, the next page can otherwise
observe a different view of the catalogue. PIT plus `search_after` can
preserve a consistent view while a customer navigates through results.

Recommended approach:

-   Offset pagination for shallow pages when page numbers are a real
    requirement.
-   `search_after` for deep result sets.
-   PIT plus `search_after` when consistent traversal is important.
-   Opaque, validated cursors rather than exposing arbitrary internal
    query state.
-   Short-lived PITs with appropriate cleanup.

Do not promise arbitrary page jumps when the chosen cursor strategy only
supports forward traversal.

------------------------------------------------------------------------

## 11. Keeping the Search Index in Sync

A search index is derived data. It can be stale, so synchronization must
be designed as a first-class part of the architecture.

Consider this failure:

1.  The catalogue database updates a product price.
2.  The database transaction commits.
3.  Publishing the update event fails.
4.  The search index continues showing the old price.

A direct "update the database, then publish to Kafka" sequence has a
dual-write failure window.

### Use a transactional outbox or CDC

A transactional outbox writes the product update and an outbox event in
the same database transaction. A publisher or CDC tool then delivers the
event to Kafka.

``` mermaid
sequenceDiagram
    participant API as Catalogue API
    participant DB as Database
    participant O as Outbox / CDC
    participant K as Kafka
    participant C as Index Consumer
    participant S as OpenSearch

    API->>DB: Update product + insert outbox event
    DB-->>API: Commit
    O->>K: Publish product event
    K->>C: Deliver event
    C->>S: Upsert search document
    S-->>C: Acknowledge
    C-->>K: Commit offset
```

### Production considerations

-   Assume events may be delivered more than once.
-   Make indexing idempotent.
-   Handle events arriving out of order using aggregate versions or a
    suitable ordering strategy.
-   Retry transient failures with backoff.
-   Send poison messages to a dead-letter queue.
-   Monitor consumer lag and index freshness.
-   Run periodic reconciliation between the source database and index.
-   Support bulk reindexing and index-version migration.
-   Define how deletes and unpublished products are propagated.

A transactional outbox provides reliable event publication, but it does
not magically guarantee correct ordering or eliminate all
synchronization issues. The consumer and data model still need those
guarantees.

### Price and inventory freshness

Different fields have different freshness requirements.

  -----------------------------------------------------------------------
  Data                                Typical strategy
  ----------------------------------- -----------------------------------
  Title and description               Asynchronous indexing is usually
                                      acceptable

  Category, brand, colour             Asynchronous indexing with lag
                                      monitoring

  Price                               Explicit freshness SLA; validate
                                      before checkout

  Inventory                           Dedicated authoritative
                                      availability source

  Order and payment state             Never rely on the search index as
                                      the authority
  -----------------------------------------------------------------------

The search API should not issue a separate database query for every
product hit to reconstruct all results. That defeats the purpose of a
search read model.

Instead, define the acceptable freshness window and validate the
authoritative offer, price, inventory, and promotions in the
cart/checkout flow.

------------------------------------------------------------------------

## 12. Performance and Reliability Checklist

### Query design

-   Use exact-match fields for structured filters.
-   Use numeric and date fields for sorting.
-   Bound page sizes, aggregation sizes, filter counts, and query
    length.
-   Avoid expensive scripts and unrestricted wildcard queries.
-   Return only fields needed for product cards.
-   Test combinations of filters, not just isolated queries.
-   Decide how missing values behave in each sort mode.

### Caching

Use caching only where it improves measured performance.

-   **CDN:** public anonymous responses when cache keys and invalidation
    are safe.
-   **Redis:** selected high-frequency search responses or other
    carefully chosen data.
-   **Search engine caches:** rely on supported behavior and measure the
    effect.

A cache key must account for all dimensions that affect the result:
query, filters, sort, page/cursor, currency, market, customer
eligibility, and relevant catalogue version.

Do not share cached personalized prices or eligibility-specific results
across customers without strict isolation.

### Cluster operations

-   Size shards based on index volume and workload measurements.
-   Use replicas for resilience and additional search capacity.
-   Monitor heap, CPU, disk, query latency, and indexing latency.
-   Benchmark bulk indexing alongside peak search traffic.
-   Test failure and recovery behavior.
-   Use index aliases and versioned mappings for controlled reindexing.

### Observability

Track at least:

-   Search API p50, p95, and p99 latency.
-   Error and timeout rates.
-   Slow queries and rejected requests.
-   Indexing lag and consumer lag.
-   Search result counts and empty-result rates.
-   Out-of-stock result frequency.
-   Search-to-product-view and search-to-cart conversion.

An illustrative initial target might be p95 under 200 ms and p99 under
500 ms for the search API. These are objectives to validate through load
testing, not guarantees. The correct targets depend on infrastructure,
query complexity, network latency, and business requirements.

------------------------------------------------------------------------

## 13. Ranking Beyond Basic Sorting

Once the basic search experience is reliable, consider a more
sophisticated default option such as "Recommended" or "Best match."

Potential signals include:

-   Text relevance.
-   Product popularity.
-   Rating and review volume.
-   Availability.
-   Delivery promise.
-   Conversion performance.
-   Personalization, where appropriate.

Keep explicit customer-selected sorts predictable. If the user chooses
"Price: low to high," an opaque ranking score should not silently
override that expectation.

### Avoid naïve rating ranking

A product with five 5-star reviews should not automatically outrank a
product with thousands of reviews averaging 4.8 stars.

A confidence-adjusted or Bayesian rating can account for review volume.
A separate ranking layer can combine signals when the customer chooses a
default recommendation mode.

Start with transparent business rules and experiments. Introduce
machine-learning ranking only when it produces measurable gains and fits
the latency and explainability requirements.

------------------------------------------------------------------------

## 14. Can Another Backend Stack Do It Better?

The search engine and data architecture are generally more important
than the choice between Java, Node.js, or Python.

### Java 21 + Spring Boot + OpenSearch

A strong fit for enterprise systems with payment integrations, catalogue
workflows, Kafka consumers, domain rules, and mature observability. It
is especially attractive when the organization already has a Java
platform.

### Node.js / TypeScript + OpenSearch

A good choice for teams that want TypeScript across frontend and
backend. It can handle I/O-heavy APIs effectively, but it does not
inherently make the search engine faster.

### Python + FastAPI + OpenSearch

Useful when catalogue search is closely connected to machine-learning
pipelines, recommendation systems, and experimentation. Python is not
automatically better for ordinary filtering and sorting.

### Managed search

A managed service can reduce operational overhead and accelerate
delivery. Compare pricing, data residency, relevance controls, indexing
limits, and vendor dependency.

### Practical decision table

  -----------------------------------------------------------------------
  Requirement                         Starting point
  ----------------------------------- -----------------------------------
  Small catalogue, simple filters     PostgreSQL or MySQL

  Complex filtering and facets        OpenSearch or Elasticsearch

  Rich text relevance                 Search engine with tuned analyzers
                                      and ranking

  Search plus ML recommendations      Search engine + separate
                                      ranking/recommendation service

  Minimal search infrastructure       Managed search
  operations                          

  Existing Java enterprise platform   Java 21 + Spring Boot + selected
                                      search engine
  -----------------------------------------------------------------------

------------------------------------------------------------------------

## 15. Suggested Implementation Roadmap

### Phase 1 --- Core API

-   Define the product search document.
-   Implement a Spring Boot search endpoint.
-   Add validated filters and an allowlisted sort policy.
-   Return product cards and pagination metadata.
-   Test representative catalogue data.

### Phase 2 --- Faceted search

-   Add brand, colour, size, price, and rating facets.
-   Define disjunctive faceting behavior.
-   Verify counts and multi-filter correctness.
-   Add integration tests against the selected search engine version.

### Phase 3 --- Reliable indexing

-   Add transactional outbox or CDC.
-   Publish product-change events.
-   Implement an idempotent index consumer.
-   Add retries, dead-letter handling, lag metrics, and reconciliation.

### Phase 4 --- Production performance

-   Benchmark realistic query combinations.
-   Add cursor pagination for deep browsing.
-   Introduce caching based on observed hot spots.
-   Add dashboards, alerts, load tests, and resilience testing.
-   Document the freshness SLA and recovery procedures.

### Phase 5 --- Better discovery

-   Tune text relevance.
-   Add popularity and rating-confidence policies.
-   Introduce autocomplete and typo tolerance if needed.
-   Evaluate recommendations and personalization through controlled
    experiments.

------------------------------------------------------------------------

## 16. Local Development Without a Database or Docker

A useful starter project should let developers experiment without
requiring them to install a database or operate a search cluster
immediately.

A practical development setup can provide two modes.

### Mode A: Local in-memory catalogue

-   Requires Java 21 and Maven.
-   Uses a sample in-memory catalogue.
-   Does not require Docker, PostgreSQL, MySQL, or OpenSearch.
-   Is suitable for exploring the REST API and business behavior.

Example:

``` bash
mvn spring-boot:run
```

Then call:

``` bash
curl "http://localhost:8080/api/v1/products?sort=PRICE_ASC"
```

**Important architectural distinction:** An in-memory adapter is a local
demonstration mode. It is not equivalent to running OpenSearch and
should not be used to claim production-scale search performance.

### Mode B: Docker Compose with OpenSearch

A Docker-based development environment can start the Spring Boot
application and OpenSearch together. The project should include:

-   A multi-stage application `Dockerfile`.
-   A Compose file for the application and search service.
-   Index mapping and initialization logic.
-   Health checks and a persistent search-data volume.
-   Configuration through environment variables.
-   Instructions for stopping and resetting the environment.

Example:

``` bash
docker compose up --build
```

Then test a combined query:

``` bash
curl "http://localhost:8080/api/v1/products?categoryId=RUNNING_SHOES&colors=BLACK&maxPrice=5000&sort=PRICE_ASC"
```

For a real deployment, secure OpenSearch with authentication and TLS,
keep credentials out of source control, and configure resource limits. A
local Compose setup should not be treated as a production deployment
template without hardening.

------------------------------------------------------------------------

## 17. Testing Strategy

A production-ready search implementation needs more than unit tests.

### Unit tests

Test:

-   Sort policy mapping.
-   Default values.
-   Invalid ranges such as minimum price greater than maximum price.
-   Maximum page size and filter-count validation.
-   Missing-value policy.
-   Response mapping.

### Integration tests

Against the actual search engine version, test:

-   Price sorting in both directions.
-   Newest and rating sorts.
-   Multiple simultaneous filters.
-   Facet counts.
-   Empty results.
-   Missing rating and price values.
-   Pagination with equal sort values.
-   Index creation and mapping compatibility.

### Reliability tests

Test:

-   Duplicate events.
-   Out-of-order product updates.
-   Consumer restart and retry behavior.
-   Search cluster unavailability.
-   Reindexing and alias cutover.
-   Database/index divergence and reconciliation.

### Performance tests

Use representative data distributions and query patterns. A benchmark
containing only a few dozen products cannot validate behavior for
millions of products. Measure latency percentiles, throughput, error
rates, indexing lag, and resource utilization under realistic
concurrency.

------------------------------------------------------------------------

## 18. Final Recommendations

For an Amazon-like product catalogue, the best solution is not to
implement a different backend endpoint for each sort option. Build one
validated search API with a clear query model.

My recommended architecture is:

-   **Backend:** Java 21 + Spring Boot.
-   **Search engine:** OpenSearch, or Elasticsearch if that is the
    selected platform.
-   **Source of truth:** PostgreSQL or MySQL.
-   **Index synchronization:** Transactional outbox or CDC with Kafka
    where warranted.
-   **Caching:** Redis and CDN based on measured workload.
-   **Pagination:** Offset for shallow pages; `search_after`, optionally
    with PIT, for deep traversal.
-   **Purchase validation:** Authoritative price, offer, and inventory
    checks at cart/checkout.
-   **Operations:** Monitoring, reconciliation, load testing, and a
    documented index-freshness SLA.

The key design lesson is this:

**Fast product discovery comes from the right read model, carefully
constructed queries, stable pagination, and reliable data
synchronization---not from sorting a large list in application memory.**

Start with a small, testable implementation, measure the workload, and
evolve the architecture according to actual catalogue size and traffic
rather than adding infrastructure prematurely.

## Further Reading

-   OpenSearch documentation: https://opensearch.org/docs/latest/
-   Elasticsearch documentation: https://www.elastic.co/guide/index.html
-   Spring Boot documentation:
    https://docs.spring.io/spring-boot/index.html
-   Spring for Apache Kafka:
    https://docs.spring.io/spring-kafka/reference/
-   PostgreSQL documentation: https://www.postgresql.org/docs/

------------------------------------------------------------------------

*Suggested Medium tags: `Software Architecture`, `System Design`,
`Java`, `Spring Boot`, `OpenSearch`*
