package co.edu.corhuila.amarantashop.sales.domain.model;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/** Test data: a valid request by default, so each test states only what it changes. */
final class OrderRequests {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    static final UUID CUSTOMER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID PRODUCT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final UUID PRODUCT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private OrderRequests() {
    }

    /** A clock fixed at a wall-clock time in Bogota. 2026-10-12 is a Monday. */
    static Clock clockAt(int year, int month, int day, int hour, int minute) {
        Instant instant = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BOGOTA).toInstant();
        return Clock.fixed(instant, BOGOTA);
    }

    /** Monday 10:00 in Bogota: the shop is open. */
    static Clock openShop() {
        return clockAt(2026, 10, 12, 10, 0);
    }

    static OrderRequest valid() {
        return new OrderRequest(CUSTOMER, CUSTOMER, Channel.ONLINE,
                List.of(new RequestedLine(PRODUCT_A, 2), new RequestedLine(PRODUCT_B, 1)),
                FulfillmentMethod.PICKUP, PaymentMethod.CASH, "Ana Torres", "3001234567", null);
    }

    static OrderRequest withLines(RequestedLine... lines) {
        return new OrderRequest(CUSTOMER, CUSTOMER, Channel.ONLINE, List.of(lines),
                FulfillmentMethod.PICKUP, PaymentMethod.CASH, "Ana Torres", "3001234567", null);
    }

    static OrderRequest withContact(String name, String phone) {
        return new OrderRequest(CUSTOMER, CUSTOMER, Channel.ONLINE, valid().lines(),
                FulfillmentMethod.PICKUP, PaymentMethod.CASH, name, phone, null);
    }

    static OrderRequest withFulfillment(FulfillmentMethod method, String address) {
        return new OrderRequest(CUSTOMER, CUSTOMER, Channel.ONLINE, valid().lines(),
                method, PaymentMethod.CASH, "Ana Torres", "3001234567", address);
    }

    /** The lines as Catalog returns them once the stock is reserved: with the name and the price. */
    static List<OrderLine> pricedLines() {
        return List.of(new OrderLine(PRODUCT_A, "Dog food 3 kg", Money.pesos(45_000), 2),
                new OrderLine(PRODUCT_B, "Cat toy", Money.pesos(12_500), 1));
    }
}
