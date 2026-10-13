package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What a client sees of an order. The domain entity is never serialized: the adapter translates it, so renaming a
 * field inside the domain cannot break a client. The names are the monolith's, so the web does not notice the move.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        UUID orderId, String status, String channel, long total, String totalFormatted, List<Line> items,
        String createdAt, String fulfillmentMethod, String paymentMethod, String contactName, String contactPhone,
        String deliveryAddress) {

    public record Line(UUID productId, String productName, long unitPrice, int quantity, long subtotal) {
    }

    public static OrderResponse of(Order order) {
        return new OrderResponse(order.id(), order.status().name(), order.channel().name(), order.total().amount(),
                format(order.total().amount()), order.lines().stream().map(OrderResponse::line).toList(),
                DateTimeFormatter.ISO_INSTANT.format(order.createdAt()), order.fulfillmentMethod().name(),
                order.paymentMethod().name(), order.contactName(), order.contactPhone(),
                order.deliveryAddress().orElse(null));
    }

    private static Line line(OrderLine l) {
        return new Line(l.productId(), l.productName(), l.unitPrice().amount(), l.quantity(), l.subtotal().amount());
    }

    /** Whole pesos as the web has always shown them: "$ 102.500", with a non-breaking space. */
    static String format(long pesos) {
        return "$ " + String.format(Locale.ROOT, "%,d", pesos).replace(',', '.');
    }
}
