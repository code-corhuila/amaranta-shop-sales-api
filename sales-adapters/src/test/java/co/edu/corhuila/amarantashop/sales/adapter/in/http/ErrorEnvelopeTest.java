package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.amarantashop.sales.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderNotFoundException;
import co.edu.corhuila.amarantashop.sales.domain.model.BusinessRuleViolation;
import co.edu.corhuila.amarantashop.sales.domain.model.InvalidStatusTransition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every error, whatever raised it, leaves in the same envelope {error, message, details?, traceId}, and the
 * translation from a domain error to a status happens in ONE place (norm 5.3, spec section 8.3).
 */
class ErrorEnvelopeTest {

    @RestController
    static class Raises {
        @GetMapping("/boom/validation")
        void validation() {
            throw new ValidationException("the request has invalid fields", List.of(new FieldError("contactName", "must not be blank")));
        }

        @GetMapping("/boom/rule")
        void rule() {
            throw new BusinessRuleViolation("INV-ORDER-008", "the shop is closed right now");
        }

        @GetMapping("/boom/transition")
        void transition() {
            throw new InvalidStatusTransition("INV-ORDER-005", "only a PENDING order can be marked as PAID");
        }

        @GetMapping("/boom/not-found")
        void notFound() {
            throw new OrderNotFoundException(UUID.randomUUID());
        }

        @GetMapping("/boom/forbidden")
        void forbidden() {
            throw new ForbiddenException("this action requires the RECEPTIONIST role");
        }

        @GetMapping("/boom/catalog")
        void catalog() {
            throw new CatalogUnavailableException("catalog timed out at http://catalog-api:8082/secret", null);
        }

        @GetMapping("/boom/unexpected")
        void unexpected() {
            throw new IllegalStateException("password authentication failed for user sales_app");
        }

        @GetMapping("/orders/{id}")
        void byId(@PathVariable UUID id) {
        }

        @PostMapping("/orders")
        void create(@RequestBody Map<String, Object> body) {
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Raises()).setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter()).build();
    }

    @Test
    void aMalformedInputIsA400WithOneEntryPerField() throws Exception {
        mvc.perform(get("/boom/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("contactName"))
                .andExpect(jsonPath("$.details[0].message").value("must not be blank"));
    }

    @Test
    void aBrokenBusinessRuleIsA422ThatNamesTheRule() throws Exception {
        mvc.perform(get("/boom/rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.details[0].field").value("rule"))
                .andExpect(jsonPath("$.details[0].message").value("INV-ORDER-008"));
    }

    @Test
    void anIllegalStatusChangeIsA422WithItsOwnCode() throws Exception {
        mvc.perform(get("/boom/transition"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.details[0].message").value("INV-ORDER-005"));
    }

    @Test
    void aMissingOrderIsA404() throws Exception {
        mvc.perform(get("/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aWrongRoleIsA403() throws Exception {
        mvc.perform(get("/boom/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void anUnreachableCatalogIsA503WithoutLeakingItsAddress() throws Exception {
        mvc.perform(get("/boom/catalog"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(not(containsString("catalog-api"))));
    }

    @Test
    void anUnexpectedErrorIsA500WithANeutralMessage() throws Exception {
        mvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("password"))));
    }

    @Test
    void anIdThatIsNotAUuidIsA400() throws Exception {
        mvc.perform(get("/orders/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0].field").value("id"));
    }

    @Test
    void malformedJsonIsA400() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void aWrongMethodIsA405WithTheEnvelope() throws Exception {
        mvc.perform(post("/boom/rule"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.traceId").exists());
    }

    // correlation (norm 5.3.9) ------------------------------------------------------------------

    @Test
    void theTraceIdRepeatsTheCorrelationIdReceived_andTheResponseReturnsIt() throws Exception {
        mvc.perform(get("/boom/rule").header("X-Correlation-Id", "e2e-saga-1"))
                .andExpect(header().string("X-Correlation-Id", "e2e-saga-1"))
                .andExpect(jsonPath("$.traceId").value("e2e-saga-1"));
    }

    @Test
    void withoutACorrelationIdOneIsGenerated() throws Exception {
        mvc.perform(get("/boom/rule"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f-]{36}")));
    }
}
