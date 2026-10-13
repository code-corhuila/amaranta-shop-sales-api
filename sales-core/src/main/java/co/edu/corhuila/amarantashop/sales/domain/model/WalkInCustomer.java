package co.edu.corhuila.amarantashop.sales.domain.model;

import java.util.UUID;

/**
 * The customer an order is attributed to when nobody identified themselves: a visitor buying online, or a
 * counter sale (spec DP-1). The identity row stays in the monolith; Sales refers to it by id only.
 */
public final class WalkInCustomer {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WalkInCustomer() {
    }
}
