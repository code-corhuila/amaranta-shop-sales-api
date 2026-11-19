package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.OrderResponse.Line;
import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.WalkInCustomer;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * What the administration sees of an order: what the customer sees, plus WHO placed it. {@code registeredCustomer} is
 * false for a visitor who bought without an account and for a counter sale (both belong to the walk-in customer), and
 * then {@code contactName} is the only name there is. Sales knows a customer by id only: the account lives in identity.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminOrderResponse(
        UUID orderId, String status, String channel, long total, String totalFormatted, int units, List<Line> items,
        String createdAt, String fulfillmentMethod, String paymentMethod, String contactName, String contactPhone,
        String deliveryAddress, UUID customerId, boolean registeredCustomer) {

    public static AdminOrderResponse of(Order order) {
        OrderResponse base = OrderResponse.of(order);
        return new AdminOrderResponse(base.orderId(), base.status(), base.channel(), base.total(), base.totalFormatted(),
                order.lines().stream().mapToInt(l -> l.quantity()).sum(), base.items(), base.createdAt(),
                base.fulfillmentMethod(), base.paymentMethod(), base.contactName(), base.contactPhone(),
                base.deliveryAddress(), order.customerId(), !WalkInCustomer.ID.equals(order.customerId()));
    }

    /** A list is always {@code {data, meta}}, like every other list of the service. */
    public record Page(List<AdminOrderResponse> data, PageResponse.Meta meta) {

        public static Page of(OrderPage page) {
            return new Page(page.data().stream().map(AdminOrderResponse::of).toList(),
                    new PageResponse.Meta(page.page(), page.limit(), page.total(), page.totalPages()));
        }
    }
}
