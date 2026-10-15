package co.edu.corhuila.amarantashop.sales.app;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.amarantashop.sales.application.port.out.ProductStock;
import co.edu.corhuila.amarantashop.sales.application.port.out.StockLine;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The service as a whole, over real HTTP: the filters, the error handler and the routes wired by the composition
 * root. These are the checks the teacher will make from outside (Annex C, "Como se verifica"), run here first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.main.allow-bean-definition-overriding=true")
class SalesHttpTest {

    private static final KeyPair KEYS = newKeyPair();
    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z"); // Monday 10:00 in Bogota
    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String ORDER = """
            {"items":[{"productId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","quantity":2}],"fulfillmentMethod":"PICKUP",
             "paymentMethod":"CASH","contactName":"Ana Torres","contactPhone":"3001234567"}""";

    @TestConfiguration
    static class Fixed {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ProductStock stock() {
            return new ProductStock() {
                @Override
                public List<StockLine> reserve(List<RequestedLine> lines) {
                    return lines.stream().map(l -> new StockLine(l.productId(), "Dog food 3 kg", Money.pesos(45_000), l.quantity())).toList();
                }

                @Override
                public void release(List<RequestedLine> lines) {
                }
            };
        }
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        registry.add("sales.jwt.public-key", () -> pem.replace("\n", "\\n"));
    }

    @Autowired
    private TestRestTemplate http;

    private HttpHeaders json(String... extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i < extra.length; i += 2) {
            headers.set(extra[i], extra[i + 1]);
        }
        return headers;
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String body, HttpHeaders headers) {
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static String bearer(String role, long exp) {
        return "Bearer " + sign("RS256", "{\"sub\":\"" + USER + "\",\"role\":\"" + role + "\",\"exp\":" + exp + "}");
    }

    // ---- authentication ------------------------------------------------------------------------------

    @Test
    void healthNeedsNoToken() {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withoutATokenEveryProtectedRouteIs401WithTheEnvelope() {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/api/v1/orders", null, json());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody().get("traceId").asText()).isNotBlank();
    }

    @Test
    void aTokenWithAnotherAlgorithmAnExpiredOneOrAForgedOneIs401() {
        long valid = NOW.getEpochSecond() + 900;
        String hs256 = "Bearer " + sign("HS256", "{\"sub\":\"" + USER + "\",\"exp\":" + valid + "}");
        String none = "Bearer " + b64("{\"alg\":\"none\"}") + "." + b64("{\"sub\":\"" + USER + "\",\"exp\":" + valid + "}") + ".";

        for (String authorization : List.of(hs256, none, bearer("CUSTOMER", NOW.getEpochSecond() - 3_600), "Bearer forged.token.here")) {
            assertThat(call(HttpMethod.GET, "/api/v1/orders", null, json("Authorization", authorization)).getStatusCode())
                    .as(authorization).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ---- the envelope and the correlation ---------------------------------------------------------------

    @Test
    void anUnknownRouteIs404WithTheEnvelope() {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/api/v1/nothing", null,
                json("Authorization", bearer("CUSTOMER", NOW.getEpochSecond() + 900)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void theTraceIdRepeatsTheCorrelationIdAndTheResponseReturnsIt() {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/api/v1/orders", null, json("X-Correlation-Id", "e2e-saga-1"));

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("e2e-saga-1");
        assertThat(response.getBody().get("traceId").asText()).isEqualTo("e2e-saga-1");
    }

    @Test
    void malformedJsonIs400() {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/orders", "{not json", json("Idempotency-Key", "key-malformed-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).isEqualTo("VALIDATION_ERROR");
    }

    // ---- the checkout, end to end ---------------------------------------------------------------------

    @Test
    void aVisitorBuysWithoutAnAccount_andARetryGetsTheSameOrder() {
        HttpHeaders headers = json("Idempotency-Key", "key-visitor-0001");

        ResponseEntity<JsonNode> first = call(HttpMethod.POST, "/api/v1/orders", ORDER, headers);
        ResponseEntity<JsonNode> retry = call(HttpMethod.POST, "/api/v1/orders", ORDER, headers);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getLocation().getPath()).startsWith("/api/v1/orders/");
        assertThat(first.getBody().get("total").asLong()).isEqualTo(90_000);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("orderId")).isEqualTo(first.getBody().get("orderId"));
    }

    @Test
    void aReceptionistMarksTheOrderAsPaid_andACustomerCannot() throws Exception {
        JsonNode created = call(HttpMethod.POST, "/api/v1/orders", ORDER, json("Idempotency-Key", "key-to-pay-0001")).getBody();
        String path = "/api/v1/orders/" + created.get("orderId").asText() + "/mark-paid";
        long exp = NOW.getEpochSecond() + 900;

        assertThat(call(HttpMethod.POST, path, null, json("Authorization", bearer("CUSTOMER", exp))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> paid = call(HttpMethod.POST, path, null, json("Authorization", bearer("RECEPTIONIST", exp)));
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(new ObjectMapper().readTree(call(HttpMethod.POST, path, null, json("Authorization", bearer("RECEPTIONIST", exp)))
                .getBody().toString()).get("error").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** "HS256" is signed with the RSA key on purpose: a wrong algorithm must be refused whatever the signature. */
    private static String sign(String alg, String claims) {
        try {
            String input = b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}") + "." + b64(claims);
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(KEYS.getPrivate());
            rsa.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
