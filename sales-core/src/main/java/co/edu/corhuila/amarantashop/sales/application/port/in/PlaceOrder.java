package co.edu.corhuila.amarantashop.sales.application.port.in;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;

/** Place an order, online or in store: one use case and one rule set for both channels (spec DP-2). */
public interface PlaceOrder {

    PlacedOrder execute(PlaceOrderCommand command);

    /** The request plus the Idempotency-Key the client sent for this intention (RN-15). */
    record PlaceOrderCommand(String idempotencyKey, OrderRequest request) {
    }

    /** {@code replayed} is true when the key had already created this order: answered 200, not 201. */
    record PlacedOrder(Order order, boolean replayed) {
    }
}
