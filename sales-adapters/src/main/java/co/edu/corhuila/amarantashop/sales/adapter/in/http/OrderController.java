package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier.Principal;
import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders;
import co.edu.corhuila.amarantashop.sales.application.port.in.MarkOrderAsPaid;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of the sales domain. The controller does three things and nothing else: it checks who calls and the
 * shape of the input, it calls one use case, and it translates the answer. The rules are the domain's.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    static final String RECEPTIONIST = "RECEPTIONIST";

    private final PlaceOrder placeOrder;
    private final MarkOrderAsPaid markOrderAsPaid;
    private final ListMyOrders listMyOrders;

    public OrderController(PlaceOrder placeOrder, MarkOrderAsPaid markOrderAsPaid, ListMyOrders listMyOrders) {
        this.placeOrder = placeOrder;
        this.markOrderAsPaid = markOrderAsPaid;
        this.listMyOrders = listMyOrders;
    }

    /** Online checkout: a visitor with no token buys for the walk-in customer; a customer buys as themselves. */
    @PostMapping
    public ResponseEntity<OrderResponse> place(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @RequestBody CheckoutRequest body, HttpServletRequest http) {
        Principal caller = caller(http);
        UUID customer = caller == null ? WalkInCustomer.ID : callerId(caller);
        return checkout(key, body, customer, customer, Channel.ONLINE, false);
    }

    /** Counter checkout: the receptionist only, and the order is attributed to them as its author. */
    @PostMapping("/in-store")
    public ResponseEntity<OrderResponse> placeInStore(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @RequestBody CheckoutRequest body, HttpServletRequest http) {
        Principal caller = caller(http);
        if (caller == null || !RECEPTIONIST.equals(caller.role())) {
            throw new ForbiddenException("this action requires the " + RECEPTIONIST + " role");
        }
        return checkout(key, body, WalkInCustomer.ID, callerId(caller), Channel.IN_STORE, true);
    }

    /** The receptionist confirms a payment. The lines, the contact data and the stock are not touched (CA-C5.3). */
    @PostMapping("/{orderId}/mark-paid")
    public OrderResponse markPaid(@PathVariable UUID orderId, HttpServletRequest http) {
        Principal caller = caller(http);
        if (caller == null || !RECEPTIONIST.equals(caller.role())) {
            throw new ForbiddenException("this action requires the " + RECEPTIONIST + " role");
        }
        return OrderResponse.of(markOrderAsPaid.execute(orderId));
    }

    /** My orders only, newest first, one bounded page at a time (limit 1 to 100, 20 by default). */
    @GetMapping
    public PageResponse listMine(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit,
                                 HttpServletRequest http) {
        Principal caller = caller(http);
        if (caller == null) {
            throw new ForbiddenException("this action requires a token");
        }
        List<FieldError> errors = new ArrayList<>();
        if (page < 1) {
            errors.add(new FieldError("page", "must be 1 or more"));
        }
        if (limit < 1 || limit > ListMyOrders.MAX_LIMIT) {
            errors.add(new FieldError("limit", "must be between 1 and " + ListMyOrders.MAX_LIMIT));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("the request has invalid fields", errors);
        }
        return PageResponse.of(listMyOrders.execute(callerId(caller), page, limit));
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
