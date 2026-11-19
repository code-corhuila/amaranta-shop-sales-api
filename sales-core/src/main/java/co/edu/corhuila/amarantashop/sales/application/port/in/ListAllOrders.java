package co.edu.corhuila.amarantashop.sales.application.port.in;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;

/** Every order of the shop, newest first, one bounded page at a time. For whoever runs the shop, never for a customer. */
public interface ListAllOrders {

    int MAX_LIMIT = ListMyOrders.MAX_LIMIT;

    /** @throws IllegalArgumentException if {@code page} is below 1 or {@code limit} is outside 1 to {@value #MAX_LIMIT} */
    OrderPage execute(int page, int limit);
}
