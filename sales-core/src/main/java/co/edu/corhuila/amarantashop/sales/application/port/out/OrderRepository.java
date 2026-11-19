package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Saves the order, its idempotency key and the durable {@code OrderCreated} outbox record in ONE local
     * transaction (RN-15). If the key already exists the new order and event are discarded and the original is
     * returned with {@code created = false}: two identical requests racing each other cannot create two orders or
     * two integration events.
     */
    SaveResult saveNew(Order order, String idempotencyKey);

    Optional<Order> findById(UUID id);

    /** Writes the status only: the lines, the contact data and the total of an order never change (RN-10). */
    void update(Order order);

    /** The orders of one customer, newest first. {@code page} starts at 1. */
    OrderPage findByCustomer(UUID customerId, int page, int limit);

    /** Every order of the shop, newest first. {@code page} starts at 1. */
    OrderPage findAll(int page, int limit);
}
