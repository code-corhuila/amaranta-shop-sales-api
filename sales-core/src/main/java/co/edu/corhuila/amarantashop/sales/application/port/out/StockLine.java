package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import java.util.UUID;

/** A reserved line as Catalog describes it: the name and the price at the moment of the purchase. */
public record StockLine(UUID productId, String productName, Money unitPrice, int quantity) {
}
