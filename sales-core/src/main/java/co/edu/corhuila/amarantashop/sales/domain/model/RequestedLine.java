package co.edu.corhuila.amarantashop.sales.domain.model;

import java.util.UUID;

/** What the buyer asked for: a product and a quantity. The name and the price come later, from Catalog. */
public record RequestedLine(UUID productId, int quantity) {
}
