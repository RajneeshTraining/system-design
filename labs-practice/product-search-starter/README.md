# Product Search Starter — Java 21 + Spring Boot + OpenSearch

A GitHub-ready starter for an e-commerce product listing/search API with sorting, filters, pagination, and brand/colour facets.

## Two ways to run

| Mode | What you need | Search implementation | Best for |
|---|---|---|---|
| **Local / no database** | Java 21 and Maven 3.9+ | In-memory sample catalogue | Learning, API development, demos, tests |
| **Docker Compose** | Docker Engine/Desktop with Compose | Spring Boot + OpenSearch container | Trying the real search-engine integration locally |

**Local mode does not require Docker, a database, Redis, Kafka, or an OpenSearch installation.** It uses a small in-memory sample dataset that resets when the application restarts. You do need Java and Maven to build/run the source. Docker mode builds the app image and starts OpenSearch for you.

> This is a runnable starter/reference implementation, not a complete Amazon-scale platform. Production use still requires security, operational sizing, event-driven catalogue indexing, price/inventory freshness rules, and workload testing.

## Features

- Java 21 and Spring Boot 3.5.x
- Search text, category, colour, brand, price range, minimum rating, and stock status
- Sort by relevance, price ascending/descending, newest, rating, popularity, and discount
- Bounded page size and deterministic tie-breakers
- Brand and colour facet counts
- In-memory implementation for zero-infrastructure local development
- OpenSearch REST integration using Java's built-in `HttpClient` (no extra OpenSearch client dependency)
- Dockerfile and Compose stack for app + OpenSearch
- Actuator health endpoint and basic API tests

## Prerequisites

### Local mode
- JDK 21
- Maven 3.9 or later

Check versions:

```bash
java -version
mvn -version
```

### Docker mode
- Docker Desktop or Docker Engine with the Compose plugin
- At least 4 GB RAM available to Docker is recommended for the app and OpenSearch together
- On Linux, OpenSearch may require `vm.max_map_count` to be at least `262144`. If startup fails with a bootstrap check, run `sudo sysctl -w vm.max_map_count=262144` and retry. Consult the OpenSearch documentation for persistent host configuration.

## Option A — Run locally without Docker or a database

From the repository root:

```bash
mvn spring-boot:run
```

The default profile uses the in-memory catalogue. Alternatively:

```bash
./scripts/run-local.sh
```

On Windows PowerShell, run:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--app.search.mode=memory"
```

Wait for Spring Boot to report that it has started, then visit:

- API: `http://localhost:8080/api/v1/products`
- Health: `http://localhost:8080/actuator/health`

No separate database or search server is used in this mode.

## Option B — Run app + OpenSearch using Docker Compose

From the repository root:

```bash
docker compose up --build
```

Compose starts:
- OpenSearch at `http://localhost:9200`
- Product Search API at `http://localhost:8080`

The app waits for OpenSearch's health check. On first startup, it creates the `products-v1` index and inserts the sample products. Index data persists in the `opensearch-data` named volume.

Try:

```bash
curl http://localhost:9200
curl http://localhost:8080/actuator/health
curl "http://localhost:8080/api/v1/products?categoryId=RUNNING_SHOES&sort=PRICE_ASC&page=0&size=5"
```

Stop the services while keeping data:

```bash
docker compose down
```

Stop services and delete the OpenSearch data volume (this removes indexed data):

```bash
docker compose down -v
```

Then run `docker compose up --build` again to create the index and seed sample data from scratch.

### Docker mode notes

- The Compose file disables OpenSearch's security plugin to keep this local demo simple. **Do not expose this configuration to an untrusted network or use it as-is in production.** Production requires authentication, TLS, network restrictions, secrets management, backups, and a supported cluster topology.
- The starter creates a one-shard, zero-replica index for local use. Production shard/replica counts must be selected from measured data volume, availability requirements, and benchmark results.
- The sample data is only inserted when the index is first created. If you already have `products-v1` in the volume, the app does not overwrite it.

## API reference

See [`docs/API.md`](docs/API.md) for all parameters and example requests.

### Quick examples

```bash
# All products
curl "http://localhost:8080/api/v1/products"

# Text search
curl "http://localhost:8080/api/v1/products?q=running"

# Running shoes under ₹5,000, black, rating >= 4, cheapest first
curl -G "http://localhost:8080/api/v1/products" \
  --data-urlencode "categoryId=RUNNING_SHOES" \
  --data-urlencode "colors=BLACK" \
  --data-urlencode "minPrice=1000" \
  --data-urlencode "maxPrice=5000" \
  --data-urlencode "minRating=4" \
  --data-urlencode "sort=PRICE_ASC" \
  --data-urlencode "page=0" \
  --data-urlencode "size=20"

# Highest-rated products
curl "http://localhost:8080/api/v1/products?sort=RATING_DESC"

# Multiple colours and brands
curl "http://localhost:8080/api/v1/products?colors=BLACK&colors=BLUE&brands=Nike&brands=Adidas"

# Health
curl "http://localhost:8080/actuator/health"
```

## Configuration

Configuration is in `src/main/resources/application.yml`. Environment variables override defaults:

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `SEARCH_MODE` | `memory` | `memory` or `opensearch` |
| `OPENSEARCH_URL` | `http://localhost:9200` | OpenSearch base URL |
| `OPENSEARCH_INDEX` | `products-v1` | Index name |

For local mode, leave `SEARCH_MODE=memory`. For Docker Compose, Compose sets `SEARCH_MODE=opensearch` and `OPENSEARCH_URL=http://opensearch:9200` inside the application container.

## Run tests

```bash
mvn test
```

The tests use the in-memory profile and do not require Docker or a database.

## Repository layout

```text
src/main/java/com/example/catalog/
  api/       REST controller, response model, API error handling
  domain/    Product model and sort enum
  search/    Search-store interface, in-memory store, OpenSearch adapter
src/main/resources/
  application.yml
  opensearch/products-v1-mapping.json
docs/
  API.md
  ARCHITECTURE.md
Dockerfile
compose.yaml
scripts/run-local.sh
```

## Important production gaps to address before real traffic

1. Replace sample products with a canonical product/catalogue source.
2. Build reliable indexing using a transactional outbox or CDC + Kafka (or another durable event mechanism), idempotent updates, retries, dead-letter handling, and reconciliation.
3. Add authentication/authorization, rate limiting, CORS policy, request-size limits, and security headers appropriate to your application.
4. Revalidate current offer price, inventory, and purchase eligibility in cart/checkout; search results can be stale.
5. Add cursor pagination (`search_after`, optionally Point in Time) for deep result traversal.
6. Define rating/discount semantics and how missing values sort. Add disjunctive facets if the UI requires counts that exclude the currently selected facet's filter.
7. Configure TLS/authentication and production-grade OpenSearch backups, monitoring, capacity, replicas, and index rollover/reindexing strategy.
8. Load-test representative filters, aggregations, page depths, and peak concurrency before selecting SLOs or shard counts.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for design notes.
