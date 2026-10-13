package co.edu.corhuila.amarantashop.sales.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A line is a snapshot of the purchase (RN-12) and its quantity is between 1 and 50 (RN-04). */
class OrderLineTest {

    private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void itsSubtotalIsThePriceTimesTheQuantity() {
        assertThat(new OrderLine(PRODUCT, "Dog food 3 kg", Money.pesos(45_000), 2).subtotal()).isEqualTo(Money.pesos(90_000));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void refusesAQuantityOutsideOneToFifty(int quantity) {
        assertThatThrownBy(() -> new OrderLine(PRODUCT, "Dog food", Money.pesos(1_000), quantity))
                .isInstanceOfSatisfying(BusinessRuleViolation.class, e -> assertThat(e.ruleId()).isEqualTo("INV-ORDER-ITEM-001"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void acceptsTheLimitsOfTheQuantity(int quantity) {
        assertThat(new OrderLine(PRODUCT, "Dog food", Money.pesos(1_000), quantity).quantity()).isEqualTo(quantity);
    }

    @Test
    void keepsTheNameAndThePriceOfThePurchase() {
        var line = new OrderLine(PRODUCT, "Dog food 3 kg", Money.pesos(45_000), 1);

        assertThat(line.productName()).isEqualTo("Dog food 3 kg");
        assertThat(line.unitPrice()).isEqualTo(Money.pesos(45_000));
    }
}
