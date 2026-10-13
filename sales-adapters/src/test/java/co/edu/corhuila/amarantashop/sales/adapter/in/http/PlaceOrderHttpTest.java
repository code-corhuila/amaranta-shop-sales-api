package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier.Principal;
import co.edu.corhuila.amarantashop.sales.adapter.out.persistence.InMemoryOrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.amarantashop.sales.application.usecase.PlaceOrderService;
import co.edu.corhuila.amarantashop.sales.domain.model.BusinessRuleViolation;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.WalkInCustomer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Placing an order over HTTP (spec HU-C1 and HU-C2): the real use cases and the in-memory repository, Catalog stubbed. */
class PlaceOrderHttpTest {

    private static final String CUSTOMER = "11111111-1111-4111-8111-111111111111";
    private static final String DOG_FOOD = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String KEY = "idem-key-0001";
    private static final String VALID = """
            {"items":[{"productId":"%s","quantity":2}],"fulfillmentMethod":"PICKUP","paymentMethod":"CASH",
             "contactName":"Ana Torres","contactPhone":"3001234567"}""".formatted(DOG_FOOD);

    private InMemoryOrderRepository orders;
    private StubStock stock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        orders = new InMemoryOrderRepository();
        stock = new StubStock();
        open(Instant.parse("2026-10-12T15:00:00Z")); // Monday 10:00 in Bogota
    }

    private void open(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var controller = new OrderController(new PlaceOrderService(orders, stock, UUID::randomUUID, o -> { }, clock));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter()).build();
    }

    private static MockHttpServletRequestBuilder place(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String userId, String role) {
        return request.requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, new Principal(userId, role));
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    // CA-C1.1 to CA-C1.3 ------------------------------------------------------------------------

    @Test
    void aVisitorWithoutATokenPlacesAnOrderForTheWalkInCustomer() throws Exception {
        send(place("/api/v1/orders", VALID).header("Idempotency-Key", KEY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/orders/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.channel").value("ONLINE"))
                .andExpect(jsonPath("$.total").value(90_000))
                .andExpect(jsonPath("$.totalFormatted").value("$ 90.000"))
                .andExpect(jsonPath("$.items[0].productName").value("Dog food 3 kg"))
                .andExpect(jsonPath("$.items[0].subtotal").value(90_000))
                .andExpect(jsonPath("$.deliveryAddress").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value(matchesPattern("2026-10-12T15:00:00Z")));

        assertThat(orders.all()).singleElement().extracting(Order::customerId).isEqualTo(WalkInCustomer.ID);
    }

    @Test
    void aCustomerWithATokenOwnsTheirOrder() throws Exception {
        send(as(place("/api/v1/orders", VALID).header("Idempotency-Key", KEY), CUSTOMER, "CUSTOMER"))
                .andExpect(status().isCreated());

        assertThat(orders.all()).singleElement().satisfies(o -> {
            assertThat(o.customerId()).isEqualTo(UUID.fromString(CUSTOMER));
            assertThat(o.createdBy()).isEqualTo(UUID.fromString(CUSTOMER));
        });
    }

    // CA-C1.6 -----------------------------------------------------------------------------------

    @Test
    void aRetryWithTheSameKeyAnswers200WithTheSameOrderAndReservesOnce() throws Exception {
        String created = send(place("/api/v1/orders", VALID).header("Idempotency-Key", KEY)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(created).get("orderId").asText();

        send(place("/api/v1/orders", VALID).header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id));

        assertThat(stock.reserveCalls).isEqualTo(1);
        assertThat(orders.all()).hasSize(1);
    }

    // CA-C1.5 and CA-C1.9: the shape of the input is a 400 with one entry per field -----------------

    @Test
    void aMissingOrBadIdempotencyKeyIs400AndNamesTheHeader() throws Exception {
        send(place("/api/v1/orders", VALID)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field", hasItem("Idempotency-Key")));
        send(place("/api/v1/orders", VALID).header("Idempotency-Key", "short")).andExpect(status().isBadRequest());
        send(place("/api/v1/orders", VALID).header("Idempotency-Key", "k".repeat(129))).andExpect(status().isBadRequest());
        assertThat(stock.reserveCalls).isZero();
    }

    @Test
    void everyInvalidFieldIsNamed() throws Exception {
        String invalid = """
                {"items":[{"productId":"nope","quantity":51},{"productId":"%s","quantity":1.5}],
                 "fulfillmentMethod":"TELEPORT","paymentMethod":"BARTER","contactName":"  ","contactPhone":""}"""
                .formatted(DOG_FOOD);

        send(place("/api/v1/orders", invalid).header("Idempotency-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", hasItem("items[0].productId")))
                .andExpect(jsonPath("$.details[*].field", hasItem("items[0].quantity")))
                .andExpect(jsonPath("$.details[*].field", hasItem("items[1].quantity")))
                .andExpect(jsonPath("$.details[*].field", hasItem("fulfillmentMethod")))
                .andExpect(jsonPath("$.details[*].field", hasItem("paymentMethod")))
                .andExpect(jsonPath("$.details[*].field", hasItem("contactName")))
                .andExpect(jsonPath("$.details[*].field", hasItem("contactPhone")));
        assertThat(stock.reserveCalls).isZero();
    }

    @Test
    void anEmptyListOfItemsIs400() throws Exception {
        String empty = VALID.replaceAll("\"items\":\\[.*?\\]", "\"items\":[]");

        send(place("/api/v1/orders", empty).header("Idempotency-Key", KEY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[*].field", hasItem("items")));
    }

    @Test
    void aDeliveryWithoutAnAddressIs400_andAPickupStoresNone() throws Exception {
        String delivery = VALID.replace("PICKUP", "DELIVERY");
        send(place("/api/v1/orders", delivery).header("Idempotency-Key", KEY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[*].field", hasItem("deliveryAddress")));

        String pickupWithAddress = VALID.replace("\"contactName\"", "\"deliveryAddress\":\"Calle 10 # 5-20\",\"contactName\"");
        send(place("/api/v1/orders", pickupWithAddress).header("Idempotency-Key", "idem-key-0002"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.deliveryAddress").doesNotExist());
    }

    // The business rules answer 422 with the id of the rule, and Catalog's failures keep the stock safe -------

    @Test
    void aClosedShopIs422WithItsRule_andCatalogIsNeverCalled() throws Exception {
        open(Instant.parse("2026-10-18T15:00:00Z")); // Sunday

        send(place("/api/v1/orders", VALID).header("Idempotency-Key", KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.details[0].message").value("INV-ORDER-008"));
        assertThat(stock.reserveCalls).isZero();
    }

    @Test
    void aProductWithoutStockIs422_andNothingIsSaved() throws Exception {
        stock.failWith(new BusinessRuleViolation("INV-PRODUCT-002", "not enough stock"));

        send(place("/api/v1/orders", VALID).header("Idempotency-Key", KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].message").value("INV-PRODUCT-002"));
        assertThat(orders.all()).isEmpty();
    }

    @Test
    void anUnreachableCatalogIs503() throws Exception {
        stock.failWith(new CatalogUnavailableException("timeout", null));

        send(place("/api/v1/orders", VALID).header("Idempotency-Key", KEY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
        assertThat(orders.all()).isEmpty();
    }
}
