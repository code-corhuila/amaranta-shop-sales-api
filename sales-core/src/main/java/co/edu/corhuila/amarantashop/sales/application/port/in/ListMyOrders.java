package co.edu.corhuila.amarantashop.sales.application.port.in;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import java.util.List;
import java.util.UUID;

/** The orders of one customer, newest first, one bounded page at a time. */
public interface ListMyOrders {

    int MAX_LIMIT = 100;

    /** @throws IllegalArgumentException if {@code page} is below 1 or {@code limit} is outside 1 to {@value #MAX_LIMIT} */
    OrderPage execute(UUID customerId, int page, int limit);

    record OrderPage(List<Order> data, int page, int limit, long total) {

        public int totalPages() {
            return (int) ((total + limit - 1) / limit);
        }
    }
}
