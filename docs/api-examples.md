# API examples (curl)

Interactive OpenAPI docs: <http://localhost:8080/swagger-ui.html> once the backend is running.

```bash
export LG=http://localhost:8080
export PW='your LEDGERGUARD_DEMO_PASSWORD'
```

## Accounts

```bash
# Create two simulated customer accounts (OPERATOR)
curl -s -u operator:$PW -H 'Content-Type: application/json' \
  -d '{"name":"Alice Cash"}' $LG/api/v1/accounts
curl -s -u operator:$PW -H 'Content-Type: application/json' \
  -d '{"name":"Bob Cash"}' $LG/api/v1/accounts

curl -s -u viewer:$PW "$LG/api/v1/accounts?page=0&size=20"
curl -s -u viewer:$PW $LG/api/v1/accounts/$ALICE/balance     # projection vs ledger-derived
curl -s -u viewer:$PW $LG/api/v1/accounts/$ALICE/entries

# Freeze (ADMIN). Closing requires a zero balance.
curl -s -u admin:$PW -X PATCH -H 'Content-Type: application/json' \
  -d '{"status":"FROZEN","reason":"fraud review"}' $LG/api/v1/accounts/$ALICE/status
```

## Money movement (OPERATOR, Idempotency-Key required)

```bash
KEY=$(uuidgen)
curl -si -u operator:$PW -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"accountId\":\"$ALICE\",\"amount\":\"1000.00\",\"description\":\"payroll\"}" \
  $LG/api/v1/transactions/deposits

# Run the SAME command again: same body, HTTP 201, header Idempotent-Replayed: true, no second deposit.

curl -si -u operator:$PW -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":\"250.00\"}" \
  $LG/api/v1/transactions/transfers

# Insufficient funds -> HTTP 422, status REJECTED, rejectionCode INSUFFICIENT_FUNDS (recorded, no entries)
curl -si -u operator:$PW -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"accountId\":\"$BOB\",\"amount\":\"999999.00\"}" $LG/api/v1/transactions/withdrawals

# Sub-cent amounts are rejected, never rounded -> HTTP 400 INVALID_AMOUNT
curl -si -u operator:$PW -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"accountId\":\"$BOB\",\"amount\":\"10.005\"}" $LG/api/v1/transactions/withdrawals
```

## Reversal (APPROVER; must not be the original initiator)

```bash
curl -s -u approver:$PW -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"reason":"customer dispute"}' $LG/api/v1/transactions/$TX_ID/reversals
```

## Queries

```bash
curl -s -u viewer:$PW "$LG/api/v1/transactions?status=REJECTED&page=0&size=20"
curl -s -u viewer:$PW $LG/api/v1/transactions/$TX_ID                # with journal entries
curl -s -u viewer:$PW "$LG/api/v1/ledger/entries?accountId=$ALICE"
curl -s -u viewer:$PW $LG/api/v1/ledger/integrity                     # all invariants, one snapshot
curl -s -u viewer:$PW $LG/api/v1/reports/overview
curl -s -u auditor:$PW "$LG/api/v1/audit-events?resourceType=Transaction"
```

## Simulation Lab (OPERATOR, only when LEDGERGUARD_SIMULATION_ENABLED=true)

```bash
curl -s -u operator:$PW -H 'Content-Type: application/json' \
  -d '{"startingBalance":"1000.00","withdrawalAmounts":["800.00","600.00"]}' \
  $LG/api/v1/simulations/concurrent-withdrawals
curl -s -u operator:$PW -H 'Content-Type: application/json' \
  -d '{"amount":"250.00","concurrentRequests":100}' $LG/api/v1/simulations/duplicate-submissions
curl -s -u operator:$PW -H 'Content-Type: application/json' -d '{"amount":"300.00"}' $LG/api/v1/simulations/rollback
curl -s -u operator:$PW -H 'Content-Type: application/json' -d '{"amount":"100.00"}' $LG/api/v1/simulations/event-recovery
```

## Error format (RFC 9457 Problem Details)

```json
{
  "type": "urn:ledgerguard:problem:idempotency-key-reused",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "This Idempotency-Key was already used for a different request. Use a new key for a new operation.",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "correlationId": "5f0c6c1e-…"
}
```
