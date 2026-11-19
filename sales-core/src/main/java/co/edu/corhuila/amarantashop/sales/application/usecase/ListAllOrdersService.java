package co.edu.corhuila.amarantashop.sales.application.usecase;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListAllOrders;
import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;

/** A list is always bounded, and this one is the longest the service has: it never answers more than one page. */
public final class ListAllOrdersService implements ListAllOrders {

    private final OrderRepository orders;

    public ListAllOrdersService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public OrderPage execute(int page, int limit) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1 or more");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return orders.findAll(page, limit);
    }
}
