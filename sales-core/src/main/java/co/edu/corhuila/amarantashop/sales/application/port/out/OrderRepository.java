package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Saves the order and its idempotency key in ONE transaction (RN-15). If the key already exists the new order
     * is discarded and the original is returned with {@code created = false}: two identical requests racing each
     * other cannot both create an order.
     */
    SaveResult saveNew(Order order, String idempotencyKey);

    Optional<Order> findById(UUID id);

    /** Writes the status only: the lines, the contact data and the total of an order never change (RN-10). */
    void update(Order order);

    /** The orders of one customer, newest first. {@code page} starts at 1. */
    OrderPage findByCustomer(UUID customerId, int page, int limit);
}
