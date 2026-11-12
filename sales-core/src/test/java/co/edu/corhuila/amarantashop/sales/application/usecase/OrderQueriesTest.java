package co.edu.corhuila.amarantashop.sales.application.usecase;

import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.CUSTOMER;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.clockAt;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.pricedLines;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderNotFoundException;
import co.edu.corhuila.amarantashop.sales.domain.model.InvalidStatusTransition;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Marking an order as paid (HU-C5) and listing my orders (HU-C6). */
class OrderQueriesTest {

    private static final UUID OTHER_CUSTOMER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private Fakes.Orders orders;
    private MarkOrderAsPaidService markAsPaid;
    private ListMyOrdersService listMine;

    @BeforeEach
    void setUp() {
        orders = new Fakes.Orders();
        markAsPaid = new MarkOrderAsPaidService(orders);
        listMine = new ListMyOrdersService(orders);
    }

    /** An order of {@code customer}, placed on a given day of October 2026 at 10:00 Bogota. */
    private Order orderOn(long id, UUID customer, int day) {
        OrderRequest base = valid();
        var request = new OrderRequest(customer, customer, base.channel(), base.lines(), base.fulfillmentMethod(),
                base.paymentMethod(), base.contactName(), base.contactPhone(), null);
        Order order = Order.place(new UUID(0, id), request, pricedLines(), clockAt(2026, 10, day, 10, 0));
        orders.saveNew(order, "key-for-order-" + id);
        return order;
    }

    // CA-C5.1 to CA-C5.3 ------------------------------------------------------------------------

    @Test
    void marksAPendingOrderAsPaidAndSavesIt() {
        Order order = orderOn(1, CUSTOMER, 12);

        Order paid = markAsPaid.execute(order.id());

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.byId.get(order.id()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.total()).isEqualTo(order.total()); // the lines and the total do not move
    }

    @Test
    void refusesToPayTwice() {
        Order order = orderOn(1, CUSTOMER, 12);
        markAsPaid.execute(order.id());

        assertThatThrownBy(() -> markAsPaid.execute(order.id()))
                .isInstanceOfSatisfying(InvalidStatusTransition.class, e -> assertThat(e.ruleId()).isEqualTo("INV-ORDER-005"));
    }

    @Test
    void reportsAnOrderThatDoesNotExist() {
        assertThatThrownBy(() -> markAsPaid.execute(new UUID(9, 9))).isInstanceOf(OrderNotFoundException.class);
    }

    // CA-C6.1 and CA-C6.2 -----------------------------------------------------------------------

    @Test
    void listsMyOrdersOnlyAndNewestFirst() {
        Order oldest = orderOn(1, CUSTOMER, 12);
        Order newest = orderOn(2, CUSTOMER, 14);
        orderOn(3, OTHER_CUSTOMER, 13);

        OrderPage page = listMine.execute(CUSTOMER, 1, 20);

        assertThat(page.data()).containsExactly(newest, oldest);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void paginatesAndSaysHowManyPagesThereAre() {
        for (int i = 1; i <= 5; i++) {
            orderOn(i, CUSTOMER, 11 + i);
        }

        OrderPage second = listMine.execute(CUSTOMER, 2, 2);

        assertThat(second.data()).hasSize(2);
        assertThat(second.page()).isEqualTo(2);
        assertThat(second.limit()).isEqualTo(2);
        assertThat(second.total()).isEqualTo(5);
        assertThat(second.totalPages()).isEqualTo(3);
    }

    @Test
    void anEmptyListIsAnEmptyPage() {
        OrderPage page = listMine.execute(CUSTOMER, 1, 20);

        assertThat(page.data()).isEmpty();
        assertThat(page.totalPages()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0, 20", "-1, 20", "1, 0", "1, 101"})
    void refusesAPageOrALimitOutOfRange(int page, int limit) {
        assertThatThrownBy(() -> listMine.execute(CUSTOMER, page, limit)).isInstanceOf(IllegalArgumentException.class);
    }
}
