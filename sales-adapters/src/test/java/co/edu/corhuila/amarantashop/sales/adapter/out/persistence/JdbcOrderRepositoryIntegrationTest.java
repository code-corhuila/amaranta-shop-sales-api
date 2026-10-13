package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.SaveResult;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The same contract as the in-memory repository, now against a real PostgreSQL with the schema of
 * amaranta-shop-sales-db, connecting as {@code sales_app} like the service does. A mock would prove that the SQL
 * was called, never that it is valid, that the mapping keeps the types or that the migration exists. Run it with
 * {@code bash scripts/integration-test.sh}; without TEST_DATABASE_URL it is skipped, not failed.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcOrderRepositoryIntegrationTest extends OrderRepositoryContract {

    private static final String URL = System.getenv("TEST_DATABASE_URL");
    private final DataSource asService = new DriverManagerDataSource(URL, System.getenv("TEST_DATABASE_USER"),
            System.getenv("TEST_DATABASE_PASSWORD"));
    private final JdbcOrderRepository repository = new JdbcOrderRepository(asService);

    @Override
    protected OrderRepository repository() {
        return repository;
    }

    /** sales_app cannot delete, by design (sales-db grants), so the administrator empties the tables between tests. */
    @BeforeEach
    void emptyTheTables() throws SQLException {
        try (Connection admin = DriverManager.getConnection(URL, System.getenv("TEST_DATABASE_ADMIN_USER"),
                System.getenv("TEST_DATABASE_ADMIN_PASSWORD")); var st = admin.createStatement()) {
            st.execute("TRUNCATE sales.orders CASCADE");
        }
    }

    private static Order orderWithAName(String name) {
        UUID product = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var request = new OrderRequest(CUSTOMER, CUSTOMER, co.edu.corhuila.amarantashop.sales.domain.model.Channel.ONLINE,
                List.of(new RequestedLine(product, 1)), co.edu.corhuila.amarantashop.sales.domain.model.FulfillmentMethod.PICKUP,
                co.edu.corhuila.amarantashop.sales.domain.model.PaymentMethod.CASH, "Ana Torres", "3001234567", null);
        return Order.place(new UUID(0, 77), request, List.of(new OrderLine(product, name, Money.pesos(1_000), 1)),
                Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void theOrderItsLinesAndItsKeyAreWrittenTogetherOrNotAtAll() {
        Order tooLongForTheColumn = orderWithAName("x".repeat(121)); // the schema allows 120 characters

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.saveNew(tooLongForTheColumn, "atomic-key-0001"))
                .isInstanceOf(RuntimeException.class);

        assertThat(repository.findById(tooLongForTheColumn.id())).isEmpty();
        assertThat(repository.findByIdempotencyKey("atomic-key-0001")).isEmpty();
    }

    @Test
    void twoRequestsWithTheSameKeyAtTheSameTimeCreateExactlyOneOrder() throws Exception {
        int clients = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<SaveResult>> results = new java.util.ArrayList<>();
            for (int i = 0; i < clients; i++) {
                long id = 100 + i;
                Callable<SaveResult> save = () -> {
                    go.await();
                    return repository.saveNew(order(id, CUSTOMER, 12), "contended-key-1");
                };
                results.add(pool.submit(save));
            }
            go.countDown();
            long created = 0;
            for (Future<SaveResult> r : results) {
                created += r.get().created() ? 1 : 0;
            }

            assertThat(created).isEqualTo(1);
            assertThat(repository.findByCustomer(CUSTOMER, 1, 100).total()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
