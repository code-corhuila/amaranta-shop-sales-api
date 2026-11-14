package co.edu.corhuila.amarantashop.sales.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Money is whole Colombian pesos, in a long: never a floating point (norm 5.2, rule 7; spec OD-1). */
class MoneyTest {

    @Test
    void addsAndMultipliesWholePesos() {
        assertThat(Money.pesos(45_000).times(2).plus(Money.pesos(12_500))).isEqualTo(Money.pesos(102_500));
    }

    @Test
    void zeroIsTheNeutralElement() {
        assertThat(Money.ZERO.plus(Money.pesos(500))).isEqualTo(Money.pesos(500));
    }

    @Test
    void refusesANegativeAmount() {
        assertThatThrownBy(() -> Money.pesos(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnAmountThatOverflows() {
        assertThatThrownBy(() -> Money.pesos(Long.MAX_VALUE).plus(Money.pesos(1))).isInstanceOf(ArithmeticException.class);
    }
}
