package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier.Principal;
import co.edu.corhuila.amarantashop.sales.adapter.out.persistence.InMemoryOrderRepository;
import co.edu.corhuila.amarantashop.sales.application.usecase.ListMyOrdersService;
import co.edu.corhuila.amarantashop.sales.application.usecase.MarkOrderAsPaidService;
import co.edu.corhuila.amarantashop.sales.application.usecase.PlaceOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Marking an order as paid (HU-C5) and listing my orders (HU-C6) over HTTP. */
class PayAndListHttpTest {

    private static final String CUSTOMER = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER = "22222222-2222-4222-8222-222222222222";
    private static final String RECEPTIONIST = "33333333-3333-4333-8333-333333333333";
    private static final String BODY = """
            {"items":[{"productId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","quantity":1}],"fulfillmentMethod":"PICKUP",
             "paymentMethod":"CASH","contactName":"Ana Torres","contactPhone":"3001234567"}""";

    private MockMvc mvc;
    private int placed;

    @BeforeEach
    void setUp() {
        var orders = new InMemoryOrderRepository();
        var clock = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
        var controller = new OrderController(new PlaceOrderService(orders, new StubStock(), UUID::randomUUID, o -> { }, clock),
                new MarkOrderAsPaidService(orders), new ListMyOrdersService(orders));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter()).build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String user, String role) {
        return request.requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, new Principal(user, role));
    }

    /** Places an order of {@code customer} and returns its id. */
    private String placeOrderOf(String customer) throws Exception {
        String json = mvc.perform(as(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-number-" + (++placed)), customer, "CUSTOMER"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(json).get("orderId").asText();
    }

    // HU-C5 -------------------------------------------------------------------------------------

    @Test
    void aReceptionistMarksAPendingOrderAsPaid_andOnlyOnce() throws Exception {
        String id = placeOrderOf(CUSTOMER);

        mvc.perform(as(post("/api/v1/orders/" + id + "/mark-paid"), RECEPTIONIST, "RECEPTIONIST"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        mvc.perform(as(post("/api/v1/orders/" + id + "/mark-paid"), RECEPTIONIST, "RECEPTIONIST"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.details[0].message").value("INV-ORDER-005"));
    }

    @Test
    void anUnknownOrderIs404_andAMalformedIdIs400() throws Exception {
        mvc.perform(as(post("/api/v1/orders/" + UUID.randomUUID() + "/mark-paid"), RECEPTIONIST, "RECEPTIONIST"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(as(post("/api/v1/orders/not-a-uuid/mark-paid"), RECEPTIONIST, "RECEPTIONIST"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void onlyTheReceptionistMayMarkAnOrderAsPaid() throws Exception {
        String id = placeOrderOf(CUSTOMER);

        mvc.perform(as(post("/api/v1/orders/" + id + "/mark-paid"), CUSTOMER, "CUSTOMER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // HU-C6 -------------------------------------------------------------------------------------

    @Test
    void aCustomerSeesTheirOwnOrdersOnly_inAnEnvelopeWithItsMeta() throws Exception {
        placeOrderOf(CUSTOMER);
        placeOrderOf(CUSTOMER);
        placeOrderOf(OTHER);

        mvc.perform(as(get("/api/v1/orders"), CUSTOMER, "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.limit").value(20)) // the default
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
    }

    @Test
    void paginatesWithTheLimitAskedFor() throws Exception {
        for (int i = 0; i < 3; i++) {
            placeOrderOf(CUSTOMER);
        }

        mvc.perform(as(get("/api/v1/orders").param("page", "2").param("limit", "2"), CUSTOMER, "CUSTOMER"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
    }

    @Test
    void aPageOrALimitOutOfRangeIs400AndNamesTheField() throws Exception {
        mvc.perform(as(get("/api/v1/orders").param("page", "0").param("limit", "101"), CUSTOMER, "CUSTOMER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", hasItem("page")))
                .andExpect(jsonPath("$.details[*].field", hasItem("limit")));
        mvc.perform(as(get("/api/v1/orders").param("limit", "many"), CUSTOMER, "CUSTOMER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutACallerThereIsNothingToList() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isForbidden());
        assertThat(placed).isZero();
    }
}
