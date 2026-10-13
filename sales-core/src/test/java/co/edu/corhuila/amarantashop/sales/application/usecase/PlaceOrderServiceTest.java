package co.edu.corhuila.amarantashop.sales.application.usecase;

import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.PRODUCT_A;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.clockAt;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.openShop;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.valid;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.withContact;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.withLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder.PlaceOrderCommand;
import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder.PlacedOrder;
import co.edu.corhuila.amarantashop.sales.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.amarantashop.sales.domain.model.BusinessRuleViolation;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderStatus;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The checkout (spec HU-C1 to HU-C3): reserve, build, save, compensate. Catalog and the database are doubles. */
class PlaceOrderServiceTest {

    private static final String KEY = "retry-key-0001";

    private Fakes.Orders orders;
    private Fakes.Stock stock;
    private Fakes.Events events;
    private PlaceOrderService service;

    @BeforeEach
    void setUp() {
        orders = new Fakes.Orders();
        stock = new Fakes.Stock();
        events = new Fakes.Events();
        service = serviceAt(openShop());
    }

    private PlaceOrderService serviceAt(Clock clock) {
        return new PlaceOrderService(orders, stock, new Fakes.SequentialIds(), events, clock);
    }

    private PlacedOrder place(OrderRequest request) {
        return service.execute(new PlaceOrderCommand(KEY, request));
    }

    // CA-C1.1 and RN-12 -------------------------------------------------------------------------

    @Test
    void createsAPendingOrderWithTheNameAndThePriceCatalogGave() {
        PlacedOrder placed = place(valid());

        assertThat(placed.replayed()).isFalse();
        assertThat(placed.order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(placed.order().total()).isEqualTo(Money.pesos(102_500));
        assertThat(placed.order().lines()).extracting("productName").containsExactly("Dog food 3 kg", "Cat toy");
        assertThat(stock.reserved).hasSize(1);
        assertThat(stock.released).isEmpty();
        assertThat(orders.byId).containsValue(placed.order());
        assertThat(events.created).containsExactly(placed.order());
    }

    // RN-15 -------------------------------------------------------------------------------------

    @Test
    void replaysTheSameOrderForTheSameKeyAndReservesOnce() {
        PlacedOrder first = place(valid());
        PlacedOrder retry = place(valid());

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.order().id()).isEqualTo(first.order().id());
        assertThat(stock.reserved).hasSize(1);
        assertThat(orders.byId).hasSize(1);
    }

    // CA-C3.3: a rule that needs no Catalog data never calls Catalog -----------------------------

    @Test
    void doesNotReserveWhenTheShopIsClosed() {
        service = serviceAt(clockAt(2026, 10, 18, 10, 0)); // Sunday

        assertThatThrownBy(() -> place(valid())).isInstanceOf(BusinessRuleViolation.class);

        assertThat(stock.reserved).isEmpty();
        assertThat(stock.released).isEmpty();
        assertThat(orders.byId).isEmpty();
    }

    @Test
    void doesNotReserveForADuplicateLineABlankContactOrAnEmptyList() {
        var duplicate = withLines(new RequestedLine(PRODUCT_A, 1), new RequestedLine(PRODUCT_A, 1));

        assertThatThrownBy(() -> place(duplicate)).isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> place(withContact(" ", "3001234567"))).isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> place(withLines())).isInstanceOf(BusinessRuleViolation.class);

        assertThat(stock.reserved).isEmpty();
    }

    // CA-C3.7 and 503 ---------------------------------------------------------------------------

    @Test
    void savesNothingAndReleasesNothingWhenCatalogRejectsTheProduct() {
        var unknown = withLines(new RequestedLine(java.util.UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), 1));

        assertThatThrownBy(() -> place(unknown))
                .isInstanceOfSatisfying(BusinessRuleViolation.class, e -> assertThat(e.ruleId()).isEqualTo("PRODUCT_NOT_FOUND"));

        assertThat(orders.byId).isEmpty();
        assertThat(stock.released).isEmpty(); // nothing was reserved, so nothing is given back
    }

    @Test
    void savesNothingWhenCatalogIsUnreachable() {
        stock.failOnReserve = new CatalogUnavailableException("catalog timed out", null);

        assertThatThrownBy(() -> place(valid())).isInstanceOf(CatalogUnavailableException.class);

        assertThat(orders.byId).isEmpty();
        assertThat(stock.released).isEmpty();
    }

    @Test
    void keepsTheReservationWhenOnlyPublishingTheEventFails() {
        events.failOnPublish = new IllegalStateException("event bus down");

        PlacedOrder placed = place(valid());

        assertThat(placed.order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orders.byId).hasSize(1);
        assertThat(stock.released).isEmpty(); // the order exists, so its stock is spoken for
    }
}
