#!/usr/bin/env sh
set -eu
BASE_URL="${BASE_URL:-http://localhost:8080}"
printf '\n== Health ==\n'
curl -fsS "$BASE_URL/actuator/health"
printf '\n\n== All products ==\n'
curl -fsS "$BASE_URL/api/v1/products"
printf '\n\n== Cheapest black running shoes <= INR 5000 ==\n'
curl -fsSG "$BASE_URL/api/v1/products" \
  --data-urlencode 'categoryId=RUNNING_SHOES' \
  --data-urlencode 'colors=BLACK' \
  --data-urlencode 'maxPrice=5000' \
  --data-urlencode 'minRating=4' \
  --data-urlencode 'sort=PRICE_ASC' \
  --data-urlencode 'page=0' \
  --data-urlencode 'size=20'
printf '\n'
