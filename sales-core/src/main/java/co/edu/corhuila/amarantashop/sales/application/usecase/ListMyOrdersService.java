package co.edu.corhuila.amarantashop.sales.application.usecase;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import java.util.UUID;

/** A list is always bounded: no query can come back with a million rows one day. */
public final class ListMyOrdersService implements ListMyOrders {

    private final OrderRepository orders;

    public ListMyOrdersService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public OrderPage execute(UUID customerId, int page, int limit) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1 or more");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return orders.findByCustomer(customerId, page, limit);
    }
}
