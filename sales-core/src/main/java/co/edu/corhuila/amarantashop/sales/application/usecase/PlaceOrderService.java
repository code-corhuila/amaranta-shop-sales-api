package co.edu.corhuila.amarantashop.sales.application.usecase;

import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder;
import co.edu.corhuila.amarantashop.sales.application.port.out.IdGenerator;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderEvents;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.ProductStock;
import co.edu.corhuila.amarantashop.sales.application.port.out.SaveResult;
import co.edu.corhuila.amarantashop.sales.application.port.out.StockLine;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The checkout. It reserves before the order exists, because the lines need the name and the price only Catalog
 * knows (spec DP-3), but it decides first everything that needs no Catalog data, so a closed shop never touches
 * the stock.
 *
 * <p>What cannot be predicted, the save failing, is compensated, not made atomic across two services: the
 * reservation is released once, never retried (RN-09). Once the order is saved its stock is spoken for, so a
 * failing event does not release it.
 */
public final class PlaceOrderService implements PlaceOrder {

    private static final System.Logger LOG = System.getLogger(PlaceOrderService.class.getName());

    private final OrderRepository orders;
    private final ProductStock stock;
    private final IdGenerator ids;
    private final OrderEvents events;
    private final Clock clock;

    public PlaceOrderService(OrderRepository orders, ProductStock stock, IdGenerator ids, OrderEvents events,
                             Clock clock) {
        this.orders = orders;
        this.stock = stock;
        this.ids = ids;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public PlacedOrder execute(PlaceOrderCommand command) {
        String key = command.idempotencyKey();
        OrderRequest request = command.request();

        // A retry of a request that already created an order gets that order back and touches no stock (RN-15).
        Optional<Order> existing = orders.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return new PlacedOrder(existing.get(), true);
        }

        Order.ensureCanBePlaced(request, clock); // no Catalog call if a rule that needs none is broken

        List<StockLine> reserved = stock.reserve(request.lines());

        SaveResult saved;
        try {
            List<OrderLine> lines = toOrderLines(request.lines(), reserved);
            saved = orders.saveNew(Order.place(ids.newId(), request, lines, clock), key);
        } catch (RuntimeException failure) {
            releaseOnce(request.lines(), failure);
            throw failure;
        }

        if (!saved.created()) { // lost the race for the same key: the winner stands, this stock goes back
            try {
                stock.release(request.lines());
            } catch (RuntimeException releaseFailure) {
                LOG.log(System.Logger.Level.ERROR, "could not release the reservation of a replayed request", releaseFailure);
            }
            return new PlacedOrder(saved.order(), true);
        }

        try {
            events.orderCreated(saved.order());
        } catch (RuntimeException eventFailure) {
            LOG.log(System.Logger.Level.WARNING, "the order exists but its event was not published", eventFailure);
        }
        return new PlacedOrder(saved.order(), false);
    }

    /** Catalog must answer for exactly what was asked: the same products and the same quantities. */
    private static List<OrderLine> toOrderLines(List<RequestedLine> asked, List<StockLine> reserved) {
        if (reserved.size() != asked.size()) {
            throw new IllegalStateException("Catalog answered " + reserved.size() + " lines for " + asked.size());
        }
        return asked.stream().map(want -> {
            StockLine got = reserved.stream()
                    .filter(l -> l.productId().equals(want.productId()) && l.quantity() == want.quantity())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Catalog did not answer for product " + want.productId()));
            return new OrderLine(got.productId(), got.productName(), got.unitPrice(), got.quantity());
        }).toList();
    }

    /** One attempt. If it fails the original error is what the caller sees, with this one attached to it. */
    private void releaseOnce(List<RequestedLine> lines, RuntimeException original) {
        try {
            stock.release(lines);
        } catch (RuntimeException releaseFailure) {
            original.addSuppressed(releaseFailure);
            LOG.log(System.Logger.Level.ERROR, "could not release the reservation: units stay reserved", releaseFailure);
        }
    }
}
