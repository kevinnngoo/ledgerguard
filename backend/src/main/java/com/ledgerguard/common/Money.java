package com.ledgerguard.common;

import java.math.BigDecimal;

/**
 * Monetary rules. Amounts are always {@link BigDecimal} (never float/double) and are stored
 * as PostgreSQL NUMERIC(19,2).
 *
 * <ul>
 *   <li>Only USD is supported; its minor unit is 2 decimal places.</li>
 *   <li>Amounts must be strictly positive. Direction is expressed by DEBIT/CREDIT, never by sign.</li>
 *   <li>Inputs with more than 2 significant decimal places are REJECTED, never silently rounded.</li>
 *   <li>The largest representable amount is 17 integer digits (NUMERIC(19,2)).</li>
 * </ul>
 */
public final class Money {

    public static final String USD = "USD";
    public static final int USD_SCALE = 2;
    private static final int MAX_INTEGER_DIGITS = 17;

    private Money() {}

    public static BigDecimal requireValidAmount(BigDecimal amount) {
        if (amount == null) {
            throw ApiException.badRequest("INVALID_AMOUNT", "amount is required");
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "amount must be greater than zero");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > USD_SCALE) {
            throw ApiException.badRequest("INVALID_AMOUNT",
                    "USD amounts support at most 2 decimal places; got " + amount.toPlainString()
                            + " (LedgerGuard never rounds amounts silently)");
        }
        if (normalized.precision() - normalized.scale() > MAX_INTEGER_DIGITS) {
            throw ApiException.badRequest("INVALID_AMOUNT", "amount exceeds the supported range");
        }
        // Exact: scale <= 2 after stripping trailing zeros, so no digits are discarded.
        return amount.setScale(USD_SCALE);
    }

    /** Returns the currency to use, defaulting to USD; rejects anything else. */
    public static String requireSupportedCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return USD;
        }
        if (!USD.equals(currency)) {
            throw ApiException.badRequest("UNSUPPORTED_CURRENCY",
                    "Unsupported currency '" + currency + "'; only USD is supported");
        }
        return USD;
    }

    public static String format(BigDecimal amount) {
        return amount.setScale(USD_SCALE).toPlainString();
    }
}
