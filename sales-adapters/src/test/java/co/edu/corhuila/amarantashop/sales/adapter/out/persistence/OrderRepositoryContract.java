package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.SaveResult;
import co.edu.corhuila.amarantashop.sales.domain.model.Channel;
import co.edu.corhuila.amarantashop.sales.domain.model.FulfillmentMethod;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderStatus;
import co.edu.corhuila.amarantashop.sales.domain.model.PaymentMethod;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every order repository must do, whichever engine is behind it. The in-memory one and the PostgreSQL one run
 * this same contract: a double that is allowed to behave differently from the real thing proves nothing.
 */
public abstract class OrderRepositoryContract {

    protected static final UUID CUSTOMER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    protected static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    protected abstract OrderRepository repository();

    /** An order of {@code customer} placed {@code day} October 2026 at 10:00 in Bogota (15:00 UTC). */
    protected static Order order(long id, UUID customer, int day) {
        var request = new OrderRequest(customer, customer, Channel.IN_STORE,
                List.of(new RequestedLine(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 2)),
                FulfillmentMethod.DELIVERY, PaymentMethod.TRANSFER, "Ana Torres", "3001234567", "Calle 10 # 5-20, Neiva");
        var lines = List.of(new OrderLine(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), "Dog food 3 kg", Money.pesos(45_000), 2));
        Clock clock = Clock.fixed(Instant.parse("2026-10-" + (day < 10 ? "0" : "") + day + "T15:00:00Z"), ZoneOffset.UTC);
        return Order.place(new UUID(0, id), request, lines, clock);
    }

    @Test
    void savesAnOrderAndReadsBackEverythingItHad() {
        Order saved = order(1, CUSTOMER, 12);

        SaveResult result = repository().saveNew(saved, "key-for-order-1");
        Order read = repository().findById(saved.id()).orElseThrow();

        assertThat(result.created()).isTrue();
        assertThat(read).usingRecursiveComparison().isEqualTo(saved);
        assertThat(read.lines()).extracting(OrderLine::productName).containsExactly("Dog food 3 kg");
        assertThat(read.total()).isEqualTo(Money.pesos(90_000));
        assertThat(read.deliveryAddress()).contains("Calle 10 # 5-20, Neiva");
    }

    @Test
    void aSecondOrderWithTheSameKeyIsDiscardedAndTheOriginalIsReturned() {
        Order first = order(1, CUSTOMER, 12);
        repository().saveNew(first, "same-key-0001");

        SaveResult second = repository().saveNew(order(2, CUSTOMER, 13), "same-key-0001");

        assertThat(second.created()).isFalse();
        assertThat(second.order().id()).isEqualTo(first.id());
        assertThat(repository().findById(new UUID(0, 2))).isEmpty(); // the loser left no trace
        assertThat(repository().findByIdempotencyKey("same-key-0001")).map(Order::id).contains(first.id());
    }

    @Test
    void anUnknownKeyOrIdIsEmpty() {
        assertThat(repository().findByIdempotencyKey("never-used-key")).isEmpty();
        assertThat(repository().findById(new UUID(9, 9))).isEmpty();
    }

    @Test
    void updatingWritesTheStatusAndNothingElse() {
        Order saved = order(1, CUSTOMER, 12);
        repository().saveNew(saved, "key-for-order-1");

        saved.markAsPaid();
        repository().update(saved);
        Order read = repository().findById(saved.id()).orElseThrow();

        assertThat(read.status()).isEqualTo(OrderStatus.PAID);
        assertThat(read.total()).isEqualTo(Money.pesos(90_000));
        assertThat(read.lines()).hasSize(1);
    }

    @Test
    void listsOnlyTheOrdersOfTheCustomer_newestFirst_inBoundedPages() {
        repository().saveNew(order(1, CUSTOMER, 12), "key-1-aaaaaaaa");
        repository().saveNew(order(2, CUSTOMER, 14), "key-2-aaaaaaaa");
        repository().saveNew(order(3, CUSTOMER, 13), "key-3-aaaaaaaa");
        repository().saveNew(order(4, OTHER, 15), "key-4-aaaaaaaa");

        OrderPage first = repository().findByCustomer(CUSTOMER, 1, 2);
        OrderPage second = repository().findByCustomer(CUSTOMER, 2, 2);

        assertThat(first.data()).extracting(Order::id).containsExactly(new UUID(0, 2), new UUID(0, 3));
        assertThat(second.data()).extracting(Order::id).containsExactly(new UUID(0, 1));
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(repository().findByCustomer(CUSTOMER, 3, 2).data()).isEmpty();
    }
}
