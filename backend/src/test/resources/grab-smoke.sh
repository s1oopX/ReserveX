#!/bin/sh
# Ad-hoc smoke for grab.lua. NOT part of the build.
#
# ⚠️ Run ONLY against a throwaway Redis, never the project instance:
#     docker run --rm -d --name rx-lua-smoke redis:7-alpine
#     docker cp backend/src/main/resources/lua/grab.lua rx-lua-smoke:/tmp/grab.lua
#     docker exec -i rx-lua-smoke sh < backend/src/test/resources/grab-smoke.sh
#     docker rm -f rx-lua-smoke
# Every case uses a distinct slot id so no key is ever reused; the script does
# not call FLUSHALL, so pointing it at a live instance would still only add keys.
set -e
R="redis-cli"
S=/tmp/grab.lua
FAIL=0
H64=$(printf 'a%.0s' $(seq 64))
B64=$(printf 'b%.0s' $(seq 64))
DAYEND=3600

chk() {
  if [ "$2" = "$3" ]; then echo "  ok   $1 = $2"
  else echo "  FAIL $1 = $2 (expected $3)"; FAIL=1; fi
}
gt() {
  if [ "$2" -gt "$3" ]; then echo "  ok   $1 = $2 (> $3)"
  else echo "  FAIL $1 = $2 (expected > $3)"; FAIL=1; fi
}
# args <rno> <slotId> <dupSuffix>
args() {
  echo "$1 $2 7 dup:2026-08-26:$3 $DAYEND 2026-08-26 9 9999999999 1234 $B64 1756000000000 pending:persist slot:full:$2 $DAYEND 5 100"
}
run() { # run <slotId> <rno> <dupSuffix>
  $R --eval $S "slot:$1:b:0" "slot:$1:b:1" "ratelimit:user:7" "ratelimit:slot:$1" , $(args "$2" "$1" "$3")
}
# The user limit is 5/s (ARGV[15]). Cases below make more than 5 grabs for user 7
# inside one second, so every case that is NOT testing rate limiting must start
# from a clean window — otherwise a later case gets -2 and the failure looks like
# a script bug rather than "the limiter is doing its job".
reset_rl() { $R del ratelimit:user:7 > /dev/null; }

reset_rl
echo "== case 1: primary bucket hit; stats keys must get a TTL =="
$R set slot:91:b:0 3 EX $DAYEND > /dev/null
$R set slot:91:b:1 3 EX $DAYEND > /dev/null
chk "return" "$(run 91 1001 $H64)" "1"
chk "bucket decremented" "$($R get slot:91:b:0)" "2"
gt  "stats:bucket TTL" "$($R ttl stats:bucket:slot:91:b:0:hit)" "0"
chk "occupy has NO ttl" "$($R ttl occupy:1001)" "-1"
chk "pending indexed" "$($R zscore pending:persist 1001)" "1756000000000"
chk "id_card_hash stored" "$($R hget occupy:1001 id_card_hash | cut -c1-3)" "bbb"
chk "bucket_no stored" "$($R hget occupy:1001 bucket_no)" "0"

echo "== case 2: rate limit returns -2 with zero side effects =="
$R set slot:92:b:0 3 EX $DAYEND > /dev/null
$R set slot:92:b:1 3 EX $DAYEND > /dev/null
$R set ratelimit:user:7 99 EX 5 > /dev/null
chk "return" "$(run 92 2002 ${H64}2)" "-2"
chk "bucket untouched" "$($R get slot:92:b:0)" "3"
chk "no occupy" "$($R exists occupy:2002)" "0"
chk "not in pending" "$($R zscore pending:persist 2002)" ""
chk "no stats key" "$($R exists stats:bucket:slot:92:b:0:hit)" "0"
chk "no dup written" "$($R exists dup:2026-08-26:${H64}2)" "0"
$R del ratelimit:user:7 > /dev/null

reset_rl
echo "== case 3: borrow path must not treat ratelimit keys as buckets =="
$R set slot:93:b:0 0 EX $DAYEND > /dev/null
$R set slot:93:b:1 2 EX $DAYEND > /dev/null
chk "return" "$(run 93 3003 ${H64}3)" "1"
chk "borrowed from b:1" "$($R get slot:93:b:1)" "1"
chk "primary untouched" "$($R get slot:93:b:0)" "0"
chk "occupy.bucket = b:1" "$($R hget occupy:3003 bucket)" "slot:93:b:1"
chk "occupy.bucket_no = 1" "$($R hget occupy:3003 bucket_no)" "1"
gt  "stats:borrow TTL" "$($R ttl stats:borrow:slot:93:b:1)" "0"
chk "ratelimit:user is a counter, not a bucket" "$($R get ratelimit:user:7)" "1"
chk "ratelimit:slot is a counter, not a bucket" "$($R get ratelimit:slot:93)" "1"

reset_rl
echo "== case 4: sold out writes slot:full and rolls back dup =="
$R set slot:94:b:0 0 EX $DAYEND > /dev/null
$R set slot:94:b:1 0 EX $DAYEND > /dev/null
chk "return" "$(run 94 4004 ${H64}4)" "0"
gt  "slot:full TTL" "$($R ttl slot:full:94)" "0"
chk "dup rolled back" "$($R exists dup:2026-08-26:${H64}4)" "0"
chk "no occupy" "$($R exists occupy:4004)" "0"

reset_rl
echo "== case 5: EXPIRE NX repairs a pre-existing no-TTL stats key =="
# Simulate a key left by the pre-fix script: exists, has a value, no TTL.
$R set stats:bucket:slot:96:b:0:hit 42 > /dev/null
chk "precondition: no TTL" "$($R ttl stats:bucket:slot:96:b:0:hit)" "-1"
$R set slot:96:b:0 3 EX $DAYEND > /dev/null
$R set slot:96:b:1 3 EX $DAYEND > /dev/null
chk "return" "$(run 96 6006 ${H64}6)" "1"
gt  "legacy key now HAS a TTL" "$($R ttl stats:bucket:slot:96:b:0:hit)" "0"
chk "counter kept counting (not reset)" "$($R get stats:bucket:slot:96:b:0:hit)" "43"

reset_rl
echo "== case 6: EXPIRE NX does not extend an existing TTL =="
$R set stats:bucket:slot:97:b:0:hit 5 EX 30 > /dev/null
$R set slot:97:b:0 3 EX $DAYEND > /dev/null
$R set slot:97:b:1 3 EX $DAYEND > /dev/null
chk "return" "$(run 97 7007 ${H64}7)" "1"
T=$($R ttl stats:bucket:slot:97:b:0:hit)
if [ "$T" -le 30 ] && [ "$T" -gt 0 ]; then echo "  ok   existing TTL preserved = $T (<= 30)"
else echo "  FAIL existing TTL became $T (expected <= 30; NX must not overwrite)"; FAIL=1; fi

reset_rl
echo "== case 7: same id card twice on another slot = quota used (-1) =="
$R set slot:95:b:0 3 EX $DAYEND > /dev/null
$R set slot:95:b:1 3 EX $DAYEND > /dev/null
chk "first" "$(run 95 5005 ${H64}5)" "1"
chk "second returns -1" "$(run 95 5006 ${H64}5)" "-1"
chk "bucket only decremented once" "$($R get slot:95:b:0)" "2"

echo ""
if [ $FAIL -eq 0 ]; then echo "ALL GRAB SMOKE CASES PASSED"; else echo "SMOKE FAILED"; exit 1; fi
