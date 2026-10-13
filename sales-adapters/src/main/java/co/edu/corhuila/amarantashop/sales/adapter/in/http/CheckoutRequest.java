package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.amarantashop.sales.domain.model.Channel;
import co.edu.corhuila.amarantashop.sales.domain.model.FulfillmentMethod;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.PaymentMethod;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The body of a checkout, in any channel. It judges only the SHAPE (400, one entry per field); the rules of the
 * business are the domain's (422). The quantity is read as raw JSON so that 1.5 is refused instead of silently
 * becoming 1.
 */
public record CheckoutRequest(String customerId, List<Item> items, String fulfillmentMethod, String paymentMethod,
                              String contactName, String contactPhone, String deliveryAddress) {

    public record Item(String productId, JsonNode quantity) {
    }

    /** Adds one entry to {@code errors} per invalid field; returns null when there is any. */
    OrderRequest toDomain(UUID defaultCustomer, UUID author, Channel channel, boolean mayNameTheCustomer,
                          List<FieldError> errors) {
        int before = errors.size();
        UUID customer = defaultCustomer;
        if (mayNameTheCustomer && customerId != null) {
            customer = uuid(customerId, "customerId", errors);
        }
        List<RequestedLine> lines = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            errors.add(new FieldError("items", "must contain at least one item"));
        } else {
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                UUID product = uuid(item.productId(), "items[" + i + "].productId", errors);
                JsonNode q = item.quantity();
                if (q == null || !q.isIntegralNumber() || q.asInt() < OrderLine.MIN_QUANTITY || q.asInt() > OrderLine.MAX_QUANTITY) {
                    errors.add(new FieldError("items[" + i + "].quantity", "must be a whole number from 1 to 50"));
                } else if (product != null) {
                    lines.add(new RequestedLine(product, q.asInt()));
                }
            }
        }
        FulfillmentMethod fulfillment = enumOf(FulfillmentMethod.class, fulfillmentMethod, "fulfillmentMethod", errors);
        PaymentMethod payment = enumOf(PaymentMethod.class, paymentMethod, "paymentMethod", errors);
        if (blank(contactName)) {
            errors.add(new FieldError("contactName", "must not be blank"));
        }
        if (blank(contactPhone)) {
            errors.add(new FieldError("contactPhone", "must not be blank"));
        }
        if (fulfillment == FulfillmentMethod.DELIVERY && blank(deliveryAddress)) {
            errors.add(new FieldError("deliveryAddress", "is required for a delivery"));
        }
        return errors.size() > before
                ? null
                : new OrderRequest(customer, author, channel, lines, fulfillment, payment, contactName, contactPhone, deliveryAddress);
    }

    private static UUID uuid(String value, String field, List<FieldError> errors) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, "must be a UUID"));
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field, List<FieldError> errors) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, "must be one of " + List.of(type.getEnumConstants())));
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
