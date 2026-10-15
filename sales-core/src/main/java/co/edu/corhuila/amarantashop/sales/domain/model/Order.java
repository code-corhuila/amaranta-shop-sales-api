package co.edu.corhuila.amarantashop.sales.domain.model;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Order aggregate root. An order is a snapshot: once placed, its lines, contact data and total never change
 * (RN-10), and the total is computed from the lines (RN-01). There is no setter; the aggregate refuses to exist
 * in an invalid state whoever calls it, so the rules live here and not in the use case.
 */
public final class Order {

    private static final ShopHours SHOP_HOURS = new ShopHours();

    private final UUID id;
    private final UUID customerId;
    private final UUID createdBy;
    private final Channel channel;
    private final List<OrderLine> lines;
    private OrderStatus status;
    private final Instant createdAt;
    private final FulfillmentMethod fulfillmentMethod;
    private final PaymentMethod paymentMethod;
    private final String contactName;
    private final String contactPhone;
    private final String deliveryAddress;

    private Order(UUID id, UUID customerId, UUID createdBy, Channel channel, List<OrderLine> lines,
                  OrderStatus status, Instant createdAt, FulfillmentMethod fulfillmentMethod,
                  PaymentMethod paymentMethod, String contactName, String contactPhone, String deliveryAddress) {
        this.id = id;
        this.customerId = customerId;
        this.createdBy = createdBy;
        this.channel = channel;
        this.lines = List.copyOf(lines);
        this.status = status;
        this.createdAt = createdAt;
        this.fulfillmentMethod = fulfillmentMethod;
        this.paymentMethod = paymentMethod;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.deliveryAddress = deliveryAddress;
    }

    /**
     * Judges everything that can be decided WITHOUT asking Catalog anything: the shop hours, the lines, the
     * contact data and the address. The use case calls it first, so a rejected order never touches the stock
     * (spec DP-3, CA-C3.3).
     */
    public static void ensureCanBePlaced(OrderRequest request, Clock clock) {
        // INV-ORDER-008 (RN-07): the shop must be open, in Bogota time, whichever the channel.
        if (!SHOP_HOURS.isOpenAt(clock.instant())) {
            throw new BusinessRuleViolation("INV-ORDER-008", "the shop is closed right now");
        }
        // INV-ORDER-004 (RN-02): at least one line.
        if (request.lines().isEmpty()) {
            throw new BusinessRuleViolation("INV-ORDER-004", "an order must have at least one line");
        }
        // INV-ORDER-ITEM-001 (RN-04): each quantity between 1 and 50.
        for (RequestedLine line : request.lines()) {
            if (line.quantity() < OrderLine.MIN_QUANTITY || line.quantity() > OrderLine.MAX_QUANTITY) {
                throw new BusinessRuleViolation("INV-ORDER-ITEM-001",
                        "the quantity of a line must be between " + OrderLine.MIN_QUANTITY + " and "
                                + OrderLine.MAX_QUANTITY);
            }
        }
        // INV-ORDER-003 (RN-03): a product at most once.
        Set<UUID> seen = new HashSet<>();
        for (RequestedLine line : request.lines()) {
            if (!seen.add(line.productId())) {
                throw new BusinessRuleViolation("INV-ORDER-003", "an order cannot contain the same product twice");
            }
        }
        // INV-ORDER-006 (RN-05): whom to contact, not a profile.
        if (isBlank(request.contactName()) || isBlank(request.contactPhone())) {
            throw new BusinessRuleViolation("INV-ORDER-006",
                    "the contact name and phone are required to place an order");
        }
        // INV-ORDER-007 (RN-06): a delivery is meaningless without an address.
        if (request.fulfillmentMethod() == FulfillmentMethod.DELIVERY && isBlank(request.deliveryAddress())) {
            throw new BusinessRuleViolation("INV-ORDER-007", "a delivery order requires a delivery address");
        }
    }

    /**
     * Builds the order from the request and from the lines Catalog priced. It judges the rules again: no caller
     * can skip {@link #ensureCanBePlaced}, because the aggregate enforces its own invariants.
     */
    public static Order place(UUID id, OrderRequest request, List<OrderLine> pricedLines, Clock clock) {
        ensureCanBePlaced(request, clock);

        Set<UUID> seen = new HashSet<>();
        for (OrderLine line : pricedLines) {
            if (!seen.add(line.productId())) {
                throw new BusinessRuleViolation("INV-ORDER-003", "an order cannot contain the same product twice");
            }
        }

        boolean delivery = request.fulfillmentMethod() == FulfillmentMethod.DELIVERY;
        return new Order(Objects.requireNonNull(id), request.customerId(), request.createdBy(), request.channel(),
                pricedLines, OrderStatus.PENDING, clock.instant(), request.fulfillmentMethod(),
                request.paymentMethod(), request.contactName().trim(), request.contactPhone().trim(),
                delivery ? request.deliveryAddress().trim() : null);
    }

    /** Rebuilds an order that already exists, as the repository read it. It validates nothing: it is history. */
    public static Order reconstitute(UUID id, UUID customerId, UUID createdBy, Channel channel,
                                     List<OrderLine> lines, OrderStatus status, Instant createdAt,
                                     FulfillmentMethod fulfillmentMethod, PaymentMethod paymentMethod,
                                     String contactName, String contactPhone, String deliveryAddress) {
        return new Order(id, customerId, createdBy, channel, lines, status, createdAt, fulfillmentMethod,
                paymentMethod, contactName, contactPhone, deliveryAddress);
    }

    /** INV-ORDER-005 (RN-11): only a PENDING order can become PAID. The one thing an order can do to itself. */
    public void markAsPaid() {
        if (status != OrderStatus.PENDING) {
            throw new InvalidStatusTransition("INV-ORDER-005", "only a PENDING order can be marked as PAID");
        }
        status = OrderStatus.PAID;
    }

    /** RN-01: the sum of the line subtotals. */
    public Money total() {
        return lines.stream().map(OrderLine::subtotal).reduce(Money.ZERO, Money::plus);
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Channel channel() {
        return channel;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public OrderStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public FulfillmentMethod fulfillmentMethod() {
        return fulfillmentMethod;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public String contactName() {
        return contactName;
    }

    public String contactPhone() {
        return contactPhone;
    }

    public Optional<String> deliveryAddress() {
        return Optional.ofNullable(deliveryAddress);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
