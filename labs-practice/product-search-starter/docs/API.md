# Product Search API

Base URL: `http://localhost:8080`

## `GET /api/v1/products`

Searches products, applies optional filters, sorts results, paginates, and returns brand/colour facet counts.

### Query parameters

| Parameter | Type | Optional | Example | Notes |
|---|---|---:|---|---|
| `q` | string | yes | `running shoes` | Searches title, brand, and description. Max length 120 characters. |
| `categoryId` | string | yes | `RUNNING_SHOES` | Exact category filter |
| `colors` | repeated string | yes | `colors=BLACK&colors=BLUE` | Matches any selected colour |
| `brands` | repeated string | yes | `brands=Nike&brands=Adidas` | Matches any selected brand |
| `minPrice` | decimal | yes | `1000` | Inclusive lower bound |
| `maxPrice` | decimal | yes | `5000` | Inclusive upper bound; must be >= minPrice |
| `minRating` | decimal | yes | `4.0` | Inclusive rating threshold from 0 to 5 |
| `inStock` | boolean | yes | `true` | Filter by stock flag |
| `sort` | enum | yes | `PRICE_ASC` | Defaults to `RELEVANCE` |
| `page` | integer | yes | `0` | Zero-based; defaults to 0; max 10000 |
| `size` | integer | yes | `20` | Defaults to 20; range 1–100 |

### Sort values

- `RELEVANCE`
- `PRICE_ASC`
- `PRICE_DESC`
- `NEWEST`
- `RATING_DESC`
- `POPULARITY_DESC`
- `DISCOUNT_DESC`

### Example request

```http
GET /api/v1/products?q=running&categoryId=RUNNING_SHOES&colors=BLACK&minPrice=1000&maxPrice=5000&minRating=4&sort=PRICE_ASC&page=0&size=20
```

Equivalent curl:

```bash
curl -G "http://localhost:8080/api/v1/products" \
  --data-urlencode "q=running" \
  --data-urlencode "categoryId=RUNNING_SHOES" \
  --data-urlencode "colors=BLACK" \
  --data-urlencode "minPrice=1000" \
  --data-urlencode "maxPrice=5000" \
  --data-urlencode "minRating=4" \
  --data-urlencode "sort=PRICE_ASC" \
  --data-urlencode "page=0" \
  --data-urlencode "size=20"
```

### Response shape

```json
{
  "items": [
    {
      "productId": "SKU-1003",
      "title": "Puma Flex Running Shoes",
      "description": "Affordable flexible running shoes",
      "categoryId": "RUNNING_SHOES",
      "brand": "Puma",
      "price": 2299.0,
      "currency": "INR",
      "discountPercentage": 25.0,
      "averageRating": 4.1,
      "ratingCount": 430,
      "colors": ["BLUE", "RED"],
      "availableSizes": ["8", "9", "10", "11"],
      "inStock": true,
      "createdAt": "2026-08-14T12:00:00Z",
      "popularityScore": 620,
      "searchable": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "facets": {
    "brands": {"Puma": 1},
    "colors": {"BLUE": 1, "RED": 1}
  }
}
```

The response above is an illustrative shape. Actual values depend on the selected filters and active search mode.

### Other endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/actuator/health` | Application health |
| `GET` | `/actuator/metrics` | Metrics endpoint (for local inspection) |

### Errors

Invalid price ranges return HTTP `400 Bad Request`. Validation errors such as unsupported sort values or invalid numeric inputs are rejected by Spring's request binding/validation. Production applications should standardize all validation errors into a documented error schema.
