package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier.Principal;
import co.edu.corhuila.amarantashop.sales.application.port.in.ListAllOrders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The orders of the whole shop, for the administrator. It sits under /api/v1/admin like the administration of the
 * catalog. The gateway only checks that a token exists; THIS decides that it is an administrator's (norm 5.3.7).
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {

    static final String ADMIN = "ADMIN";

    private final ListAllOrders listAllOrders;

    public AdminOrderController(ListAllOrders listAllOrders) {
        this.listAllOrders = listAllOrders;
    }

    /** Every order, newest first, one bounded page at a time (limit 1 to 100, 20 by default). */
    @GetMapping
    public AdminOrderResponse.Page list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit,
                                        HttpServletRequest http) {
        Principal caller = OrderController.caller(http);
        if (caller == null || !ADMIN.equals(caller.role())) {
            throw new ForbiddenException("this action requires the " + ADMIN + " role");
        }
        List<FieldError> errors = new ArrayList<>();
        if (page < 1) {
            errors.add(new FieldError("page", "must be 1 or more"));
        }
        if (limit < 1 || limit > ListAllOrders.MAX_LIMIT) {
            errors.add(new FieldError("limit", "must be between 1 and " + ListAllOrders.MAX_LIMIT));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("the request has invalid fields", errors);
        }
        return AdminOrderResponse.Page.of(listAllOrders.execute(page, limit));
    }
}
