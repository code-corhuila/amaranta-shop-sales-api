package co.edu.corhuila.amarantashop.sales.application.usecase;

import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.PRODUCT_A;
import static co.edu.corhuila.amarantashop.sales.domain.model.OrderRequests.PRODUCT_B;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.application.port.out.IdGenerator;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderEvents;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.ProductStock;
import co.edu.corhuila.amarantashop.sales.application.port.out.SaveResult;
import co.edu.corhuila.amarantashop.sales.application.port.out.StockLine;
import co.edu.corhuila.amarantashop.sales.domain.model.BusinessRuleViolation;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Doubles of the output ports. They record what the use case did, so a test can say what must NOT have happened. */
final class Fakes {

    private Fakes() {
    }

    /** Catalog: knows two products, records every call, and can be told to fail. */
    static class Stock implements ProductStock {
        final List<List<RequestedLine>> reserved = new ArrayList<>();
        final List<List<RequestedLine>> released = new ArrayList<>();
        RuntimeException failOnReserve;
        RuntimeException failOnRelease;
        private final Map<UUID, StockLine> catalog = new HashMap<>();

        Stock() {
            catalog.put(PRODUCT_A,
                    new StockLine(PRODUCT_A,
                            "Dog food 3 kg", Money.pesos(45_000), 0));
            catalog.put(PRODUCT_B,
                    new StockLine(PRODUCT_B,
                            "Cat toy", Money.pesos(12_500), 0));
        }

        @Override
        public List<StockLine> reserve(List<RequestedLine> lines) {
            reserved.add(lines);
            if (failOnReserve != null) {
                throw failOnReserve;
            }
            return lines.stream().map(l -> {
                StockLine known = catalog.get(l.productId());
                if (known == null) {
                    throw new BusinessRuleViolation("PRODUCT_NOT_FOUND", "the product does not exist");
                }
                return new StockLine(known.productId(), known.productName(), known.unitPrice(), l.quantity());
            }).toList();
        }

        @Override
        public void release(List<RequestedLine> lines) {
            released.add(lines);
            if (failOnRelease != null) {
                throw failOnRelease;
            }
        }
    }

    /** The order repository, with the idempotency key and the two ways a save can go wrong. */
    static final class Orders implements OrderRepository {
        final Map<UUID, Order> byId = new HashMap<>();
        final Map<String, UUID> byKey = new HashMap<>();
        RuntimeException failOnSave;
        Order winnerOfTheRace;

        @Override
        public Optional<Order> findByIdempotencyKey(String key) {
            return winnerOfTheRace != null ? Optional.empty() : Optional.ofNullable(byKey.get(key)).map(byId::get);
        }

        @Override
        public SaveResult saveNew(Order order, String idempotencyKey) {
            if (failOnSave != null) {
                throw failOnSave;
            }
            if (winnerOfTheRace != null) {
                return new SaveResult(winnerOfTheRace, false);
            }
            if (byKey.containsKey(idempotencyKey)) {
                return new SaveResult(byId.get(byKey.get(idempotencyKey)), false);
            }
            byId.put(order.id(), order);
            byKey.put(idempotencyKey, order.id());
            return new SaveResult(order, true);
        }

        @Override
        public Optional<Order> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public void update(Order order) {
            byId.put(order.id(), order);
        }

        @Override
        public OrderPage findByCustomer(UUID customerId, int page, int limit) {
            List<Order> mine = byId.values().stream().filter(o -> o.customerId().equals(customerId))
                    .sorted(Comparator.comparing(Order::createdAt).reversed()).toList();
            int from = Math.min((page - 1) * limit, mine.size());
            return new OrderPage(mine.subList(from, Math.min(from + limit, mine.size())), page, limit, mine.size());
        }
    }

    static final class Events implements OrderEvents {
        final List<Order> created = new ArrayList<>();
        RuntimeException failOnPublish;

        @Override
        public void orderCreated(Order order) {
            if (failOnPublish != null) {
                throw failOnPublish;
            }
            created.add(order);
        }
    }

    static final class SequentialIds implements IdGenerator {
        private int next = 1;

        @Override
        public UUID newId() {
            return new UUID(0, next++);
        }
    }
}
