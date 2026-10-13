package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import java.util.Optional;

public interface OrderRepository {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Saves the order and its idempotency key in ONE transaction (RN-15). If the key already exists the new order
     * is discarded and the original is returned with {@code created = false}: two identical requests racing each
     * other cannot both create an order.
     */
    SaveResult saveNew(Order order, String idempotencyKey);
}
