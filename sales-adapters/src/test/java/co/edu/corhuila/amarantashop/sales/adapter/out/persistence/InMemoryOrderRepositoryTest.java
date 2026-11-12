package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;

class InMemoryOrderRepositoryTest extends OrderRepositoryContract {

    private final InMemoryOrderRepository repository = new InMemoryOrderRepository();

    @Override
    protected OrderRepository repository() {
        return repository;
    }
}
