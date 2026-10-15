package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.amarantashop.sales.domain.model.Channel;
import co.edu.corhuila.amarantashop.sales.domain.model.FulfillmentMethod;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.PaymentMethod;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The representation is a translation: the domain entity is never serialized (norm 5.3). */
class OrderResponseTest {

    private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void translatesAnOrderWithItsLinesAndTheirSubtotals() {
        var request = new OrderRequest(PRODUCT, PRODUCT, Channel.ONLINE, List.of(new RequestedLine(PRODUCT, 2)),
                FulfillmentMethod.PICKUP, PaymentMethod.CARD, "Ana Torres", "3001234567", null);
        Order order = Order.place(new UUID(0, 1), request, List.of(new OrderLine(PRODUCT, "Dog food 3 kg", Money.pesos(45_000), 2)),
                Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC));

        OrderResponse response = OrderResponse.of(order);

        assertThat(response.orderId()).isEqualTo(new UUID(0, 1));
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.total()).isEqualTo(90_000);
        assertThat(response.createdAt()).isEqualTo("2026-10-12T15:00:00Z"); // RFC 3339, UTC
        assertThat(response.deliveryAddress()).isNull();
        assertThat(response.items()).singleElement().satisfies(line -> {
            assertThat(line.productName()).isEqualTo("Dog food 3 kg");
            assertThat(line.subtotal()).isEqualTo(90_000);
        });
    }

    @ParameterizedTest
    @CsvSource({"0, '$ 0'", "8500, '$ 8.500'", "102500, '$ 102.500'", "1234567, '$ 1.234.567'"})
    void showsWholePesosAsTheWebHasAlwaysShownThem(long pesos, String expected) {
        assertThat(OrderResponse.format(pesos)).isEqualTo(expected);
    }
}
