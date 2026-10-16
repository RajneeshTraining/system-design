#!/usr/bin/env bash
# Demo: fire known + unknown email lookups and show how many DB calls the Bloom filter avoided.
# Usage: BASE=http://localhost:8080 KNOWN_MAX=200000 bash scripts/try-it.sh
BASE="${BASE:-http://localhost:8080}"
KNOWN_MAX="${KNOWN_MAX:-200000}"
N="${N:-2000}"

echo "Checking filter is ready..."
until curl -s "$BASE/api/admin/bloom/stats" | grep -q '"ready":true'; do sleep 1; done

echo "Sending $N lookups for UNKNOWN emails..."
for i in $(seq 1 "$N"); do
  curl -s -o /dev/null "$BASE/api/users?email=ghost$i@nowhere.org"
done

echo "Sending $((N/10)) lookups for KNOWN emails..."
for i in $(seq 1 $((N/10))); do
  curl -s -o /dev/null "$BASE/api/users?email=user$((RANDOM % KNOWN_MAX))@example.com"
done

echo; echo "Stats:"
curl -s "$BASE/api/admin/bloom/stats" | python3 -m json.tool 2>/dev/null || curl -s "$BASE/api/admin/bloom/stats"
