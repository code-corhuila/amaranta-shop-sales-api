package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.application.port.out.ProductStock;
import co.edu.corhuila.amarantashop.sales.application.port.out.StockLine;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.RequestedLine;
import java.util.List;
import java.util.UUID;

/** Catalog for the HTTP tests: it knows one product, counts the calls and can be told to fail. */
final class StubStock implements ProductStock {

    static final UUID DOG_FOOD = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    int reserveCalls;
    int releaseCalls;
    private RuntimeException failure;

    void failWith(RuntimeException error) {
        failure = error;
    }

    @Override
    public List<StockLine> reserve(List<RequestedLine> lines) {
        reserveCalls++;
        if (failure != null) {
            throw failure;
        }
        return lines.stream()
                .map(l -> new StockLine(l.productId(), "Dog food 3 kg", Money.pesos(45_000), l.quantity()))
                .toList();
    }

    @Override
    public void release(List<RequestedLine> lines) {
        releaseCalls++;
    }
}
