-- Simulated external clearing account. Deposits and withdrawals post against it,
-- so value is never created or destroyed without a balancing journal entry.
INSERT INTO accounts (id, account_number, name, account_type, normal_balance, currency, status,
                      allow_negative, system_account)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYS-CLEARING-USD',
        'Simulated External Clearing (USD)', 'ASSET', 'DEBIT', 'USD', 'ACTIVE', FALSE, TRUE);

INSERT INTO account_balances (account_id, balance, allow_negative)
VALUES ('00000000-0000-0000-0000-000000000001', 0, FALSE);
