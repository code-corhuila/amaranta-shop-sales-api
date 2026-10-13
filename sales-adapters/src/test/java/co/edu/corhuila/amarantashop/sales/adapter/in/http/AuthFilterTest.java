package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who may call what (spec RN-14, DP-1): every /api/ route needs a valid token, except that a visitor with NO token
 * may place an online order. A token that is present but broken is a 401, never a silent guest purchase (CA-C1.4).
 */
class AuthFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final String USER = "11111111-1111-4111-8111-111111111111";

    @RestController
    static class Probe {
        @PostMapping("/api/v1/orders")
        String place(HttpServletRequest request) {
            return who(request);
        }

        @PostMapping("/api/v1/orders/in-store")
        String inStore(HttpServletRequest request) {
            return who(request);
        }

        @GetMapping("/api/v1/orders")
        String list(HttpServletRequest request) {
            return who(request);
        }

        @GetMapping("/health")
        String health() {
            return "ok";
        }

        private static String who(HttpServletRequest request) {
            var principal = (Rs256Verifier.Principal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
            return principal == null ? "guest" : principal.userId() + ":" + principal.role();
        }
    }

    private TestTokens tokens;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tokens = new TestTokens();
        var filter = new AuthFilter(new Rs256Verifier(tokens.publicKeyAsEnvValue()), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter(), filter).build();
    }

    private String bearer(String role) {
        return "Bearer " + tokens.token(Map.of("sub", USER, "role", role, "exp", NOW.getEpochSecond() + 900));
    }

    @Test
    void healthNeedsNoToken() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void aRouteUnderApiWithoutATokenIs401WithTheEnvelopeAndTheTraceId() throws Exception {
        mvc.perform(get("/api/v1/orders").header("X-Correlation-Id", "trace-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("trace-1"));
    }

    @Test
    void aValidTokenReachesTheRouteAndTheControllerKnowsWhoAndWhatRole() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(USER + ":CUSTOMER"));
    }

    @Test
    void aVisitorWithoutATokenMayPlaceAnOnlineOrder() throws Exception {
        mvc.perform(post("/api/v1/orders")).andExpect(status().isOk()).andExpect(content().string("guest"));
    }

    @Test
    void theGuestExceptionIsOnlyForPlacingAnOnlineOrder() throws Exception {
        mvc.perform(post("/api/v1/orders/in-store")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBrokenTokenOnTheGuestRouteIs401NotAGuestPurchase() throws Exception {
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + TestTokens.signedByAnotherKey(
                Map.of("sub", USER, "role", "CUSTOMER", "exp", NOW.getEpochSecond() + 900))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + tokens.token(
                Map.of("sub", USER, "role", "CUSTOMER", "exp", NOW.getEpochSecond() - 3_600))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("invalid or has expired")));
    }

    @Test
    void anAuthorizationHeaderThatIsNotABearerTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").header("Authorization", "")).andExpect(status().isUnauthorized());
    }
}
