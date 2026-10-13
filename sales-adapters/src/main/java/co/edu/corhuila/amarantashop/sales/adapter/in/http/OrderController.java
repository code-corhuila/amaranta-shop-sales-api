package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier.Principal;
import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder;
import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder.PlaceOrderCommand;
import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder.PlacedOrder;
import co.edu.corhuila.amarantashop.sales.domain.model.Channel;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.WalkInCustomer;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of the sales domain. The controller does three things and nothing else: it checks who calls and the
 * shape of the input, it calls one use case, and it translates the answer. The rules are the domain's.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final PlaceOrder placeOrder;

    public OrderController(PlaceOrder placeOrder) {
        this.placeOrder = placeOrder;
    }

    /** Online checkout: a visitor with no token buys for the walk-in customer; a customer buys as themselves. */
    @PostMapping
    public ResponseEntity<OrderResponse> place(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @RequestBody CheckoutRequest body, HttpServletRequest http) {
        Principal caller = caller(http);
        UUID customer = caller == null ? WalkInCustomer.ID : callerId(caller);
        return checkout(key, body, customer, customer, Channel.ONLINE, false);
    }

    private ResponseEntity<OrderResponse> checkout(String key, CheckoutRequest body, UUID customer, UUID author,
                                                   Channel channel, boolean mayNameTheCustomer) {
        List<FieldError> errors = new ArrayList<>();
        if (key == null || key.length() < 8 || key.length() > 128) {
            errors.add(new FieldError("Idempotency-Key", "header required, 8 to 128 characters"));
        }
        OrderRequest request = body.toDomain(customer, author, channel, mayNameTheCustomer, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException("the request has invalid fields", errors);
        }
        PlacedOrder placed = placeOrder.execute(new PlaceOrderCommand(key, request));
        OrderResponse response = OrderResponse.of(placed.order());
        if (placed.replayed()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/v1/orders/" + placed.order().id())).body(response);
    }

    static Principal caller(HttpServletRequest http) {
        return (Principal) http.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
    }

    static UUID callerId(Principal caller) {
        try {
            return UUID.fromString(caller.userId());
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("the token does not identify a user");
        }
    }
}
