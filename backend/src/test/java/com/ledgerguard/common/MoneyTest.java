package com.ledgerguard.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({
            "1, 1.00",
            "0.01, 0.01",
            "250.5, 250.50",
            "100.000, 100.00",          // trailing zeros carry no value: accepted, not rounded
            "99999999999999999.99, 99999999999999999.99"
    })
    void acceptsValidAmountsAndNormalizesScaleExactly(String input, String expected) {
        assertThat(Money.requireValidAmount(new BigDecimal(input))).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    void rejectsZeroAndNegativeAmounts(String input) {
        assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal(input)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("greater than zero");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.005", "0.001", "1.999"})
    void rejectsSubCentAmountsInsteadOfRounding(String input) {
        assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal(input)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("never rounds");
    }

    @Test
    void rejectsAmountsBeyondNumeric19Scale2() {
        assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal("100000000000000000.00")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("supported range");
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> Money.requireValidAmount(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void defaultsToUsdAndRejectsOtherCurrencies() {
        assertThat(Money.requireSupportedCurrency(null)).isEqualTo("USD");
        assertThat(Money.requireSupportedCurrency("USD")).isEqualTo("USD");
        assertThatThrownBy(() -> Money.requireSupportedCurrency("EUR"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only USD");
    }
}
