package co.edu.corhuila.amarantashop.sales.adapter.out.catalog;

import co.edu.corhuila.amarantashop.sales.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.amarantashop.sales.application.port.out.ProductStock;
import co.edu.corhuila.amarantashop.sales.application.port.out.StockLine;
import co.edu.corhuila.amarantashop.sales.domain.model.BusinessRuleViolation;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The only way Sales reaches the stock: Catalog is its sole owner (RN-08). Two INTERNAL endpoints that the gateway
 * never publishes (spec DP-8): {@code POST /internal/products/reserve-stock} and {@code release-stock}. Both
 * timeouts are declared. A 422 from Catalog is a rule of the business; anything else, including a timeout and an
 * answer that is not JSON, is Catalog being unavailable. A release is sent once and never retried: a retry would
 * credit the stock twice.
 */
public class CatalogStockHttpAdapter implements ProductStock {

    private final URI base;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public CatalogStockHttpAdapter(String baseUrl, Duration connectTimeout, Duration requestTimeout) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
    }

    /** A record, not a Map.of: its fields are written in a fixed order, so the body is always the same. */
    private record Item(String productId, int quantity) {
    }

    @Override
    public List<StockLine> reserve(List<RequestedLine> lines) {
        JsonNode answer = send("reserve-stock", lines, 200);
        try {
            List<StockLine> reserved = new ArrayList<>();
            for (JsonNode line : answer.get("lines")) {
                reserved.add(new StockLine(UUID.fromString(line.get("productId").asText()), line.get("productName").asText(),
                        Money.pesos(line.get("unitPriceAmount").asLong()), line.get("quantity").asInt()));
            }
            return reserved;
        } catch (RuntimeException e) {
            throw new CatalogUnavailableException("Catalog answered something that is not a list of lines", e);
        }
    }

    @Override
    public void release(List<RequestedLine> lines) {
        send("release-stock", lines, 204);
    }

    private JsonNode send(String operation, List<RequestedLine> lines, int expectedStatus) {
        try {
            var items = lines.stream().map(l -> new Item(l.productId().toString(), l.quantity())).toList();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/internal/products/" + operation))
                    .timeout(requestTimeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("items", items))));
            String correlationId = MDC.get("correlationId");
            if (correlationId != null) {
                request.header("X-Correlation-Id", correlationId);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 422) {
                throw ruleOf(response.body());
            }
            if (response.statusCode() != expectedStatus) {
                throw new CatalogUnavailableException("Catalog answered " + response.statusCode() + " to " + operation, null);
            }
            return expectedStatus == 204 ? null : json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogUnavailableException("interrupted while asking Catalog for " + operation, e);
        } catch (IOException e) {
            throw new CatalogUnavailableException("Catalog could not be reached for " + operation, e);
        }
    }

    /** Catalog's envelope names the rule under {@code details[field=rule]}, as this service's own does (spec OD-2). */
    private RuntimeException ruleOf(String body) {
        try {
            JsonNode envelope = json.readTree(body);
            for (JsonNode detail : envelope.path("details")) {
                if ("rule".equals(detail.path("field").asText())) {
                    return new BusinessRuleViolation(detail.path("message").asText(), envelope.path("message").asText("rejected by Catalog"));
                }
            }
            return new BusinessRuleViolation("CATALOG-REJECTED", envelope.path("message").asText("rejected by Catalog"));
        } catch (IOException e) {
            return new CatalogUnavailableException("Catalog rejected the request with an unreadable answer", e);
        }
    }
}
