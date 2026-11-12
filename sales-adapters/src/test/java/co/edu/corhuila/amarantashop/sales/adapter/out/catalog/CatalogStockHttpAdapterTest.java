package co.edu.corhuila.amarantashop.sales.adapter.out.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.amarantashop.sales.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.amarantashop.sales.application.port.out.StockLine;
import co.edu.corhuila.amarantashop.sales.domain.model.BusinessRuleViolation;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Sales never touches the stock of Catalog: it asks over two internal endpoints (spec DP-8, section 8.5). Catalog is
 * a real HTTP server in the test, so the request really leaves and the answer really comes back.
 */
class CatalogStockHttpAdapterTest {

    private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private HttpServer catalog;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> correlationIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String answer = "";
    private volatile long delayMillis;

    @BeforeEach
    void startCatalog() throws IOException {
        catalog = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalog.createContext("/internal/products", exchange -> {
            calls.incrementAndGet();
            bodies.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            correlationIds.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-Id")));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : out.length);
            if (status != 204) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        catalog.start();
    }

    @AfterEach
    void stopCatalog() {
        catalog.stop(0);
        MDC.clear();
    }

    private CatalogStockHttpAdapter adapter() {
        return new CatalogStockHttpAdapter("http://127.0.0.1:" + catalog.getAddress().getPort(),
                Duration.ofMillis(500), Duration.ofMillis(400));
    }

    private static final List<RequestedLine> ASKED = List.of(new RequestedLine(PRODUCT, 2));

    @Test
    void reservesTheItemsAndAnswersWithTheNameAndPriceOfEachLine() {
        answer = "{\"lines\":[{\"productId\":\"" + PRODUCT + "\",\"productName\":\"Dog food 3 kg\",\"unitPriceAmount\":45000,\"quantity\":2}]}";

        List<StockLine> lines = adapter().reserve(ASKED);

        assertThat(lines).containsExactly(new StockLine(PRODUCT, "Dog food 3 kg", Money.pesos(45_000), 2));
        assertThat(bodies).singleElement().isEqualTo(
                "POST /internal/products/reserve-stock {\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":2}]}");
    }

    @Test
    void aRuleOfCatalogBecomesABusinessRuleViolationWithItsId() {
        status = 422;
        answer = "{\"error\":\"BUSINESS_RULE_VIOLATION\",\"message\":\"not enough stock\",\"details\":[{\"field\":\"rule\",\"message\":\"INV-PRODUCT-002\"}]}";

        assertThatThrownBy(() -> adapter().reserve(ASKED))
                .isInstanceOfSatisfying(BusinessRuleViolation.class, e -> assertThat(e.ruleId()).isEqualTo("INV-PRODUCT-002"));
    }

    @Test
    void aFailureOfCatalogIsUnavailableNotARuleOfTheBusiness() {
        status = 500;
        answer = "{\"error\":\"INTERNAL_ERROR\"}";

        assertThatThrownBy(() -> adapter().reserve(ASKED)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void anUnreachableCatalogIsUnavailable() {
        catalog.stop(0);

        assertThatThrownBy(() -> adapter().reserve(ASKED)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void aCatalogThatDoesNotAnswerInTimeIsUnavailable_andTheCallerIsNotLeftWaiting() {
        delayMillis = 1_500; // the adapter gives up after 400 ms
        answer = "{\"lines\":[]}";

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter().reserve(ASKED)).isInstanceOf(CatalogUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_200));
    }

    @Test
    void anAnswerThatIsNotWhatWasPromisedIsUnavailable() {
        answer = "<html>proxy error</html>";

        assertThatThrownBy(() -> adapter().reserve(ASKED)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void releasesTheItems() {
        status = 204;

        adapter().release(ASKED);

        assertThat(bodies).singleElement().asString().startsWith("POST /internal/products/release-stock ");
    }

    @Test
    void aReleaseThatFailsIsNeverRetried() {
        status = 500;

        assertThatThrownBy(() -> adapter().release(ASKED)).isInstanceOf(CatalogUnavailableException.class);

        assertThat(calls.get()).isEqualTo(1); // a retry could credit the stock twice
    }

    @Test
    void passesTheCorrelationIdOnSoTheTraceCrossesTheService() {
        answer = "{\"lines\":[]}";
        MDC.put("correlationId", "e2e-saga-1");

        adapter().reserve(ASKED);

        assertThat(correlationIds).containsExactly("e2e-saga-1");
    }
}
