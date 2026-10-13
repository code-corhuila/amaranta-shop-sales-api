package co.edu.corhuila.amarantashop.sales.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Everything known about an order BEFORE Catalog is asked for anything. {@link Order#ensureCanBePlaced} judges
 * it, so a closed shop, a duplicate line or a blank contact never reserves a single unit (spec DP-3, CA-C3.3).
 */
public record OrderRequest(
        UUID customerId,
        UUID createdBy,
        Channel channel,
        List<RequestedLine> lines,
        FulfillmentMethod fulfillmentMethod,
        PaymentMethod paymentMethod,
        String contactName,
        String contactPhone,
        String deliveryAddress) {

    public OrderRequest {
        lines = List.copyOf(lines);
    }
}
