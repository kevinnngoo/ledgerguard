# ADR 0002: BigDecimal money and a genuine double-entry ledger

**Status:** Accepted

## Decision
- Money is `java.math.BigDecimal` in Java and `NUMERIC(19,2)` in PostgreSQL. `float` and `double` are never used, because 0.1 + 0.2 ≠ 0.3 in binary floating point.
- Only USD is supported. Its minor unit is 2 decimal places. Amounts with more precision are **rejected** (`INVALID_AMOUNT`), never rounded. Trailing zeros (`100.000`) are accepted because they carry no value.
- Amounts are always positive. Direction is expressed by DEBIT/CREDIT, never by sign.
- Every posting writes balanced journal entries (debits = credits). Customer accounts are liabilities (credit-normal); the clearing account is an asset (debit-normal). Deposits and withdrawals post against clearing, so value is never created or destroyed.
- JSON carries amounts as strings (`"250.00"`) so JavaScript clients do not lose precision.

## Alternatives considered
- **Single-entry balances** (`UPDATE balance = balance - x`): simpler, but offers no audit trail and no way to verify a balance independently.
- **Integer cents (`long`)**: also correct and fast, but less self-describing, and harder to extend to currencies with 0 or 3 minor digits.

## Consequences
Every balance can be recomputed from entries (`ledgerDerivedBalance`). The integrity check proves `SUM(debits) = SUM(credits)` across the whole ledger.
