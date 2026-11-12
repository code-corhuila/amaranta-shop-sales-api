package co.edu.corhuila.amarantashop.sales.app;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.AuthFilter;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.ErrorHandler;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.HealthController;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.OrderController;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.amarantashop.sales.adapter.out.catalog.CatalogStockHttpAdapter;
import co.edu.corhuila.amarantashop.sales.adapter.out.persistence.InMemoryOrderRepository;
import co.edu.corhuila.amarantashop.sales.adapter.out.persistence.JdbcOrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders;
import co.edu.corhuila.amarantashop.sales.application.port.in.MarkOrderAsPaid;
import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder;
import co.edu.corhuila.amarantashop.sales.application.port.out.IdGenerator;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderEvents;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.ProductStock;
import co.edu.corhuila.amarantashop.sales.application.usecase.ListMyOrdersService;
import co.edu.corhuila.amarantashop.sales.application.usecase.MarkOrderAsPaidService;
import co.edu.corhuila.amarantashop.sales.application.usecase.PlaceOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The composition root: the ONE place that knows every concrete type and every limit (norm 5.3.10). The core knows
 * only its ports; here each port gets its adapter, and each timeout and pool size is written with its value.
 */
@Configuration
public class SalesConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SalesConfiguration.class);

    // ---- outside: the database, Catalog ----------------------------------------------------------------

    /** With no DATABASE_URL the service runs on an in-memory repository (local runs); otherwise PostgreSQL as sales_app. */
    @Bean
    OrderRepository orderRepository(@Value("${sales.database.url:}") String url, @Value("${sales.database.user:}") String user,
                                    @Value("${sales.database.password:}") String password,
                                    @Value("${sales.database.pool-max:10}") int poolMax,
                                    @Value("${sales.database.statement-timeout-ms:5000}") long statementTimeoutMs) {
        if (url.isBlank()) {
            LOG.warn("DATABASE_URL is not set: orders are kept in memory and lost on restart");
            return new InMemoryOrderRepository();
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(user);
        pool.setPassword(password);
        pool.setMaximumPoolSize(poolMax);                                    // pool size: declared
        pool.setConnectionTimeout(Duration.ofSeconds(5).toMillis());         // wait for a connection: 5 s
        pool.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs); // time per statement: declared
        return new JdbcOrderRepository(new HikariDataSource(pool));
    }

    @Bean
    ProductStock productStock(@Value("${sales.catalog.url}") String catalogUrl) {
        return new CatalogStockHttpAdapter(catalogUrl, Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    // ---- the use cases ------------------------------------------------------------------------------------

    /**
     * The system clock. CLOCK_FIXED_AT exists only so a demo or a smoke test can run inside the shop hours at any
     * time of day: it makes the service believe it is that instant, and it says so loudly. Never set it in production.
     */
    @Bean
    Clock clock(@Value("${sales.clock.fixed-at:}") String fixedAt) {
        if (fixedAt.isBlank()) {
            return Clock.systemUTC();
        }
        LOG.warn("CLOCK_FIXED_AT is set: the service believes it is {}. For demos and smoke tests, never for production", fixedAt);
        return Clock.fixed(Instant.parse(fixedAt), ZoneOffset.UTC);
    }

    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }

    /** The announcement that an order exists is a log line today; the broker arrives with the worker. */
    @Bean
    OrderEvents orderEvents() {
        Logger events = LoggerFactory.getLogger("events");
        return order -> events.info("OrderCreated orderId={} channel={} total={}", order.id(), order.channel(), order.total().amount());
    }

    @Bean
    PlaceOrder placeOrder(OrderRepository orders, ProductStock stock, IdGenerator ids, OrderEvents events, Clock clock) {
        return new PlaceOrderService(orders, stock, ids, events, clock);
    }

    @Bean
    MarkOrderAsPaid markOrderAsPaid(OrderRepository orders) {
        return new MarkOrderAsPaidService(orders);
    }

    @Bean
    ListMyOrders listMyOrders(OrderRepository orders) {
        return new ListMyOrdersService(orders);
    }

    // ---- HTTP in: the routes, the error handler and the two filters ---------------------------------------

    @Bean
    OrderController orderController(PlaceOrder place, MarkOrderAsPaid markPaid, ListMyOrders list) {
        return new OrderController(place, markPaid, list);
    }

    @Bean
    HealthController healthController() {
        return new HealthController();
    }

    @Bean
    ErrorHandler errorHandler() {
        return new ErrorHandler();
    }

    /** The public key of amaranta-shop-security: inline (a .env value) or in a file. The service holds no private key. */
    @Bean
    Rs256Verifier verifier(@Value("${sales.jwt.public-key:}") String inline, @Value("${sales.jwt.public-key-file:}") String file) throws IOException {
        if (inline.isBlank() && file.isBlank()) {
            throw new IllegalStateException("set JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE: the service verifies tokens itself");
        }
        return new Rs256Verifier(inline.isBlank() ? Files.readString(Path.of(file)) : inline);
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        var registration = new FilterRegistrationBean<>(new CorrelationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE); // first: even a rejected request has a correlation id
        return registration;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json, Clock clock) {
        var registration = new FilterRegistrationBean<>(new AuthFilter(verifier, json, clock));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
