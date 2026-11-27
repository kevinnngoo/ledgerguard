#!/usr/bin/env bash
# SQL-level proof of LedgerGuard's concurrency strategy, independent of Java.
#
# Creates a DISPOSABLE database (ledgerguard_sqldemo), applies the Flyway migrations with psql,
# then fires real concurrent psql sessions:
#   1. UNSAFE read-then-write withdrawals (no row lock)  -> lost updates / overdraft
#   2. SAFE   SELECT ... FOR UPDATE withdrawals           -> exactly floor(balance/amount) succeed
#   3. 100 concurrent requests sharing one idempotency key -> exactly one executes
# The database is dropped at the end. Uses standard PG* env vars (PGHOST, PGPORT, PGUSER, PGPASSWORD).
# Do NOT point this at a database you care about; it only touches ledgerguard_sqldemo.
set -euo pipefail

DB=ledgerguard_sqldemo
CONCURRENCY=${CONCURRENCY:-25}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

psql -v ON_ERROR_STOP=1 -qAt -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB" >/dev/null
for f in "$MIGRATIONS"/V*.sql; do
  psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$f" >/dev/null
done
P="psql -qAt -d $DB"

SAFE=aaaaaaaa-0000-0000-0000-000000000001
UNSAFE=aaaaaaaa-0000-0000-0000-000000000002
$P -v ON_ERROR_STOP=1 <<SQL
INSERT INTO accounts (id, account_number, name, account_type, normal_balance, currency, status, allow_negative)
VALUES ('$SAFE', 'DEMO-SAFE', 'Safe', 'LIABILITY', 'CREDIT', 'USD', 'ACTIVE', false),
       ('$UNSAFE', 'DEMO-UNSAFE', 'Unsafe', 'LIABILITY', 'CREDIT', 'USD', 'ACTIVE', true);
INSERT INTO account_balances (account_id, balance, allow_negative) VALUES ('$SAFE', 1000, false), ('$UNSAFE', 1000, true);
SQL

unsafe() {
  $P -c "DO \$\$ DECLARE b numeric; BEGIN
    SELECT balance INTO b FROM account_balances WHERE account_id = '$UNSAFE';   -- read without lock
    PERFORM pg_sleep(0.05);                                                       -- widen the race window
    IF b >= 100 THEN
      UPDATE account_balances SET balance = b - 100 WHERE account_id = '$UNSAFE'; -- write stale value back
      RAISE NOTICE 'APPROVED';
    ELSE RAISE NOTICE 'REJECTED'; END IF; END \$\$;" 2>&1 | grep -oE 'APPROVED|REJECTED' || true
}

safe() {
  $P -c "BEGIN;
    SELECT 1 FROM account_balances WHERE account_id = '$SAFE' FOR UPDATE;          -- row lock
    DO \$\$ DECLARE b numeric; BEGIN
      SELECT balance INTO b FROM account_balances WHERE account_id = '$SAFE';
      PERFORM pg_sleep(0.05);
      IF b >= 100 THEN
        UPDATE account_balances SET balance = balance - 100 WHERE account_id = '$SAFE';
        RAISE NOTICE 'APPROVED';
      ELSE RAISE NOTICE 'REJECTED'; END IF; END \$\$;
    COMMIT;" 2>&1 | grep -oE 'APPROVED|REJECTED' || true
}

idempotent() {
  $P -c "BEGIN; DO \$\$ DECLARE claimed bigint; BEGIN
    INSERT INTO idempotency_records (client_id, idempotency_key, operation, request_hash)
    VALUES ('demo', 'KEY-1', 'WITHDRAWAL', repeat('a', 64))
    ON CONFLICT (client_id, idempotency_key) DO NOTHING RETURNING id INTO claimed;
    IF claimed IS NULL THEN RAISE NOTICE 'REPLAYED'; RETURN; END IF;
    PERFORM 1 FROM account_balances WHERE account_id = '$SAFE' FOR UPDATE;
    PERFORM pg_sleep(0.1);
    UPDATE account_balances SET balance = balance - 250 WHERE account_id = '$SAFE';
    UPDATE idempotency_records SET response_status = 201, completed_at = now() WHERE id = claimed;
    RAISE NOTICE 'EXECUTED'; END \$\$; COMMIT;" 2>&1 | grep -oE 'REPLAYED|EXECUTED' || true
}

run_parallel() { # fn count outfile
  local i
  for i in $(seq "$2"); do "$1" >>"$3" & done
  wait
}

balance() { $P -c "SELECT balance FROM account_balances WHERE account_id = '$1'"; }

echo "== $CONCURRENCY concurrent \$100 withdrawals from a \$1000.00 balance =="
run_parallel unsafe "$CONCURRENCY" "$WORK/unsafe"
UA=$(grep -c APPROVED "$WORK/unsafe" || true)
echo "UNSAFE (no lock):   approved=$UA  final balance=$(balance $UNSAFE)  (paid out \$$((UA * 100)) from \$1000)"

run_parallel safe "$CONCURRENCY" "$WORK/safe"
SA=$(grep -c APPROVED "$WORK/safe" || true)
SB=$(balance $SAFE)
echo "SAFE (FOR UPDATE):  approved=$SA  final balance=$SB"

$P -c "UPDATE account_balances SET balance = 1000 WHERE account_id = '$SAFE'" >/dev/null
echo "== 100 concurrent requests with one idempotency key, each withdrawing \$250 =="
run_parallel idempotent 100 "$WORK/idem"
EX=$(grep -c EXECUTED "$WORK/idem" || true)
RP=$(grep -c REPLAYED "$WORK/idem" || true)
IB=$(balance $SAFE)
echo "executed=$EX replayed=$RP final balance=$IB"

psql -qAt -d postgres -c "DROP DATABASE $DB" >/dev/null

FAIL=0
[ "$SA" -eq 10 ] || { echo "FAIL: expected exactly 10 safe approvals"; FAIL=1; }
[ "$SB" = "0.00" ] || { echo "FAIL: expected safe balance 0.00"; FAIL=1; }
[ "$EX" -eq 1 ] || { echo "FAIL: expected exactly one idempotent execution"; FAIL=1; }
[ "$IB" = "750.00" ] || { echo "FAIL: expected balance 750.00 after idempotent withdrawal"; FAIL=1; }
[ $FAIL -eq 0 ] && echo "PASS: locking and idempotency invariants held"
exit $FAIL
