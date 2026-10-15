package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.util.List;

/**
 * What Sales needs from Catalog, the only owner of stock (RN-08). Sales never touches the data of Catalog: it
 * asks. A product that does not exist or lacks stock is a {@code BusinessRuleViolation}; an unreachable Catalog
 * is a {@link CatalogUnavailableException}.
 */
public interface ProductStock {

    /** Takes the stock of every line, or of none, and answers with the name and price of each product. */
    List<StockLine> reserve(List<RequestedLine> lines);

    /** Gives back what {@link #reserve} took. At most once per reservation, never retried: a retry would credit twice. */
    void release(List<RequestedLine> lines);
}
