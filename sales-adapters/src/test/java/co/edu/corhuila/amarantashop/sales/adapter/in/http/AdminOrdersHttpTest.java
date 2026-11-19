package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier.Principal;
import co.edu.corhuila.amarantashop.sales.adapter.out.persistence.InMemoryOrderRepository;
import co.edu.corhuila.amarantashop.sales.application.usecase.ListAllOrdersService;
import co.edu.corhuila.amarantashop.sales.application.usecase.ListMyOrdersService;
import co.edu.corhuila.amarantashop.sales.application.usecase.MarkOrderAsPaidService;
import co.edu.corhuila.amarantashop.sales.application.usecase.PlaceOrderService;
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

/** The administration reads every order of the shop, and says who placed each one. Nobody else may. */
class AdminOrdersHttpTest {

    private static final String CUSTOMER = "11111111-1111-4111-8111-111111111111";
    private static final String ADMIN = "44444444-4444-4444-8444-444444444444";
    private static final String BODY = """
            {"items":[{"productId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","quantity":2}],"fulfillmentMethod":"PICKUP",
             "paymentMethod":"CASH","contactName":"Ana Torres","contactPhone":"3001234567"}""";

    private MockMvc mvc;
    private int placed;

    @BeforeEach
    void setUp() throws Exception {
        var orders = new InMemoryOrderRepository();
        var clock = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
        var shop = new OrderController(new PlaceOrderService(orders, new StubStock(), UUID::randomUUID, o -> { }, clock),
                new MarkOrderAsPaidService(orders), new ListMyOrdersService(orders));
        mvc = MockMvcBuilders.standaloneSetup(shop, new AdminOrderController(new ListAllOrdersService(orders)))
                .setControllerAdvice(new ErrorHandler()).addFilters(new CorrelationFilter()).build();
        buy(CUSTOMER);   // a registered customer
        buy(null);       // a visitor with no token
    }

    private void buy(String customer) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Idempotency-Key", "key-number-" + (++placed));
        if (customer != null) {
            request = as(request, customer, "CUSTOMER");
        }
        mvc.perform(request).andExpect(status().isCreated());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String user, String role) {
        return request.requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, new Principal(user, role));
    }

    @Test
    void theAdministratorSeesEveryOrder_andWhetherItsCustomerHasAnAccount() throws Exception {
        mvc.perform(as(get("/api/v1/admin/orders"), ADMIN, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[?(@.registeredCustomer == true)].customerId").value(CUSTOMER))
                .andExpect(jsonPath("$.data[?(@.registeredCustomer == false)].customerId")
                        .value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.data[0].contactName").value("Ana Torres"))
                .andExpect(jsonPath("$.data[0].units").value(2))
                .andExpect(jsonPath("$.data[0].items", hasSize(1)));
    }

    @Test
    void theListIsBounded_oneValidPageAtATime() throws Exception {
        mvc.perform(as(get("/api/v1/admin/orders?page=2&limit=1"), ADMIN, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        mvc.perform(as(get("/api/v1/admin/orders?limit=101"), ADMIN, "ADMIN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("limit"));
        mvc.perform(as(get("/api/v1/admin/orders?page=0"), ADMIN, "ADMIN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("page"));
    }

    @Test
    void aCustomerOrAReceptionistCannotReadTheOrdersOfEveryone() throws Exception {
        mvc.perform(as(get("/api/v1/admin/orders"), CUSTOMER, "CUSTOMER")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/orders"), ADMIN, "RECEPTIONIST")).andExpect(status().isForbidden());
    }

    @Test
    void whoHasNoTokenCannotEither() throws Exception {
        mvc.perform(get("/api/v1/admin/orders")).andExpect(status().isForbidden());
    }
}
