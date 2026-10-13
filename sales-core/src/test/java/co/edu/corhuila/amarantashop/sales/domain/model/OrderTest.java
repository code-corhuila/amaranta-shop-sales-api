package co.edu.corhuila.amarantashop.sales.domain.model;

import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.PRODUCT_A;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.clockAt;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.openShop;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.pricedLines;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.valid;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.withContact;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.withFulfillment;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.withLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The rules of the Order aggregate (spec checkout.md, section 4). Each test names the rule it proves. */
class OrderTest {

    private static final UUID ORDER_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    private static Order placed() {
        return Order.place(ORDER_ID, valid(), pricedLines(), openShop());
    }

    private static void assertRule(Runnable action, String ruleId) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.ruleId()).isEqualTo(ruleId));
    }

    // RN-01 -------------------------------------------------------------------------------------

    @Test
    void totalEqualsTheSumOfTheLines() {
        // 2 x 45,000 + 1 x 12,500
        assertThat(placed().total()).isEqualTo(Money.pesos(102_500));
    }

    @Test
    void startsPendingWithTheChannelTheAuthorAndTheClockTime() {
        Order order = placed();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.channel()).isEqualTo(Channel.ONLINE);
        assertThat(order.customerId()).isEqualTo(OrderRequests.CUSTOMER);
        assertThat(order.createdBy()).isEqualTo(OrderRequests.CUSTOMER);
        assertThat(order.createdAt()).isEqualTo(openShop().instant());
        assertThat(order.id()).isEqualTo(ORDER_ID);
    }

    // RN-02 -------------------------------------------------------------------------------------

    @Test
    void rejectsAnOrderWithoutLines() {
        assertRule(() -> Order.ensureCanBePlaced(withLines(), openShop()), "INV-ORDER-004");
    }

    // RN-03 -------------------------------------------------------------------------------------

    @Test
    void rejectsTheSameProductTwice() {
        var twice = withLines(new RequestedLine(PRODUCT_A, 1), new RequestedLine(PRODUCT_A, 2));

        assertRule(() -> Order.ensureCanBePlaced(twice, openShop()), "INV-ORDER-003");
    }

    // RN-04 -------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void rejectsAQuantityOutsideOneToFifty(int quantity) {
        var request = withLines(new RequestedLine(PRODUCT_A, quantity));

        assertRule(() -> Order.ensureCanBePlaced(request, openShop()), "INV-ORDER-ITEM-001");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void acceptsTheLimitsOfTheQuantity(int quantity) {
        var request = withLines(new RequestedLine(PRODUCT_A, quantity));

        assertThatCode(() -> Order.ensureCanBePlaced(request, openShop())).doesNotThrowAnyException();
    }

    // RN-05 -------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t  "})
    void rejectsBlankContactData(String blank) {
        assertRule(() -> Order.ensureCanBePlaced(withContact(blank, "3001234567"), openShop()), "INV-ORDER-006");
        assertRule(() -> Order.ensureCanBePlaced(withContact("Ana Torres", blank), openShop()), "INV-ORDER-006");
    }

    @Test
    void keepsTheContactDataWithoutItsSurroundingSpaces() {
        Order order = Order.place(ORDER_ID, withContact("  Ana Torres ", " 3001234567  "), pricedLines(), openShop());

        assertThat(order.contactName()).isEqualTo("Ana Torres");
        assertThat(order.contactPhone()).isEqualTo("3001234567");
    }

    // RN-06 -------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void requiresAnAddressForDelivery(String address) {
        assertRule(() -> Order.ensureCanBePlaced(withFulfillment(FulfillmentMethod.DELIVERY, address), openShop()),
                "INV-ORDER-007");
        assertRule(() -> Order.ensureCanBePlaced(withFulfillment(FulfillmentMethod.DELIVERY, null), openShop()),
                "INV-ORDER-007");
    }

    @Test
    void keepsTheAddressOfADelivery() {
        var request = withFulfillment(FulfillmentMethod.DELIVERY, " Calle 10 # 5-20, Neiva ");

        assertThat(Order.place(ORDER_ID, request, pricedLines(), openShop()).deliveryAddress())
                .contains("Calle 10 # 5-20, Neiva");
    }

    @Test
    void dropsTheAddressOnPickup() {
        var request = withFulfillment(FulfillmentMethod.PICKUP, "Calle 10 # 5-20, Neiva");

        assertThat(Order.place(ORDER_ID, request, pricedLines(), openShop()).deliveryAddress()).isEmpty();
    }

    // RN-07 -------------------------------------------------------------------------------------

    @Test
    void rejectsOrdersWhileTheShopIsClosed() {
        assertRule(() -> Order.ensureCanBePlaced(valid(), clockAt(2026, 10, 12, 12, 0)), "INV-ORDER-008"); // lunch
        assertRule(() -> Order.ensureCanBePlaced(valid(), clockAt(2026, 10, 12, 7, 59)), "INV-ORDER-008");  // before
        assertRule(() -> Order.ensureCanBePlaced(valid(), clockAt(2026, 10, 12, 18, 0)), "INV-ORDER-008");  // closing
        assertRule(() -> Order.ensureCanBePlaced(valid(), clockAt(2026, 10, 18, 10, 0)), "INV-ORDER-008");  // Sunday
    }

    @Test
    void acceptsAnOrderAtOpeningTime() {
        assertThatCode(() -> Order.ensureCanBePlaced(valid(), clockAt(2026, 10, 12, 8, 0))).doesNotThrowAnyException();
    }

    @Test
    void theShopHoursAreThoseOfBogotaWhateverTheZoneOfTheClock() {
        // 13:30 UTC is 08:30 in Bogota (open). Read as UTC it would fall in the 12-14 lunch break (closed).
        var clock = Clock.fixed(Instant.parse("2026-10-12T13:30:00Z"), ZoneOffset.UTC);

        assertThatCode(() -> Order.ensureCanBePlaced(valid(), clock)).doesNotThrowAnyException();
    }

    @Test
    void checksTheShopHoursAgainWhenThePlacedOrderIsBuilt() {
        // the aggregate enforces its own rules: no caller can skip ensureCanBePlaced
        assertRule(() -> Order.place(ORDER_ID, valid(), pricedLines(), clockAt(2026, 10, 18, 10, 0)), "INV-ORDER-008");
    }
}
