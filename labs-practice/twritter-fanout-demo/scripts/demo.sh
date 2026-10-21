#!/usr/bin/env bash
# End-to-end demo of the hybrid timeline against a running instance.
#   ./scripts/demo.sh                      # uses http://localhost:8080
#   BASE_URL=http://localhost:9090 ./scripts/demo.sh
# Requires: bash, curl. Handles are suffixed with a random number so the script can be re-run.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"
SUFFIX="$RANDOM"

post() { curl -sS -X POST -H 'Content-Type: application/json' "$@"; }
id_of() { grep -o '"id":"[0-9]*"' | head -1 | cut -d'"' -f4; }

new_user() { post "$API/users" -d "{\"handle\":\"$1_$SUFFIX\"}" | id_of; }
new_tweet() { post "$API/tweets" -d "{\"authorId\":\"$1\",\"text\":\"$2\"}" | id_of; }

echo "== 1. Create users"
STAR=$(new_user star);   ALICE=$(new_user alice)
F1=$(new_user fan1); F2=$(new_user fan2); F3=$(new_user fan3)
echo "star=$STAR alice=$ALICE fan1=$F1 fan2=$F2 fan3=$F3"

echo "== 2. fan1..fan3 follow star (3 followers = celebrity at the default threshold)"
for f in "$F1" "$F2" "$F3"; do curl -sS -X POST "$API/users/$f/follow/$STAR" >/dev/null; done
curl -sS "$API/users/$STAR"; echo

echo "== 3. fan1 also follows alice (a normal author with 1 follower)"
curl -sS -X POST "$API/users/$F1/follow/$ALICE" >/dev/null

echo "== 4. Both post a tweet"
new_tweet "$ALICE" "Hello from a normal author (pushed to followers)" >/dev/null
new_tweet "$STAR"  "Hello from a celebrity (pulled at read time)"     >/dev/null
sleep 0.5   # async fan-out in the background

echo "== 5. fan1 home timeline: expect BOTH tweets, newest first"
curl -sS "$API/users/$F1/timeline/home"; echo

echo "== 6. fan2 home timeline: expect only the celebrity tweet"
curl -sS "$API/users/$F2/timeline/home"; echo

echo "== 7. Idempotent create: same Idempotency-Key twice returns the same tweet id"
KEY="demo-$SUFFIX"
A=$(post "$API/tweets" -H "Idempotency-Key: $KEY" -d "{\"authorId\":\"$ALICE\",\"text\":\"once only\"}" | id_of)
B=$(post "$API/tweets" -H "Idempotency-Key: $KEY" -d "{\"authorId\":\"$ALICE\",\"text\":\"once only\"}" | id_of)
echo "first=$A second=$B"
[ "$A" = "$B" ] && echo "OK: identical" || { echo "FAIL: ids differ"; exit 1; }

echo "== Done. Watch the server log for 'skipping push fan-out' (celebrity) vs 'pushed to N follower timelines'."
