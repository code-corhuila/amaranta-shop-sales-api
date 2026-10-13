package co.edu.corhuila.amarantashop.sales.domain.model;

import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.PRODUCT_A;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.PRODUCT_B;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.openShop;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.pricedLines;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The only thing an order can do to itself: go from PENDING to PAID (rules RN-10 and RN-11). */
class OrderPaymentTest {

    private static final UUID ORDER_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    private static Order placed() {
        return Order.place(ORDER_ID, valid(), pricedLines(), openShop());
    }

    @Test
    void onlyAPendingOrderCanBePaid() {
        Order order = placed();

        order.markAsPaid();

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(order::markAsPaid)
                .isInstanceOfSatisfying(InvalidStatusTransition.class, e -> assertThat(e.ruleId()).isEqualTo("INV-ORDER-005"));
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void anOrderThatWasNeverPendingCannotBePaid() {
        Order cancelled = Order.reconstitute(ORDER_ID, OrderRequests.CUSTOMER, OrderRequests.CUSTOMER, Channel.ONLINE,
                pricedLines(), OrderStatus.CANCELLED, openShop().instant(), FulfillmentMethod.PICKUP,
                PaymentMethod.CASH, "Ana Torres", "3001234567", null);

        assertThatThrownBy(cancelled::markAsPaid).isInstanceOf(InvalidStatusTransition.class);
    }

    @Test
    void hasNoMutatorExceptPay() {
        // RN-10: lines, contact data and total never change; the status only goes PENDING -> PAID.
        List<String> mutators = Arrays.stream(Order.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getReturnType() == void.class)
                .map(Method::getName)
                .toList();

        assertThat(mutators).containsExactly("markAsPaid");
    }

    @Test
    void theLinesOfAnOrderCannotBeChangedFromOutside() {
        Order order = placed();

        assertThatThrownBy(() -> order.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(order.lines()).extracting(OrderLine::productId).containsExactly(PRODUCT_A, PRODUCT_B);
    }
}
