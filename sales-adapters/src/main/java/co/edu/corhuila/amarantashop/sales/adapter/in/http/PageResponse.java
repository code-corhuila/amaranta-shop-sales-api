package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import java.util.List;

/** A list is always {@code {data, meta}}: the items and which page of how many this is. */
public record PageResponse(List<OrderResponse> data, Meta meta) {

    public record Meta(int page, int limit, long total, int totalPages) {
    }

    public static PageResponse of(OrderPage page) {
        return new PageResponse(page.data().stream().map(OrderResponse::of).toList(),
                new Meta(page.page(), page.limit(), page.total(), page.totalPages()));
    }
}
