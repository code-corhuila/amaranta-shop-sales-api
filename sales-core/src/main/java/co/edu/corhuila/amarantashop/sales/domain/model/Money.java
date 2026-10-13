package co.edu.corhuila.amarantashop.sales.domain.model;

/**
 * An amount of money in whole Colombian pesos. A long, never a floating point (norm 5.2, rule 7): the
 * monolith stored whole pesos and the spec keeps them (OD-1), so nothing is converted.
 */
public record Money(long amount) {

    public static final Money ZERO = new Money(0);

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("an amount of money cannot be negative: " + amount);
        }
    }

    public static Money pesos(long amount) {
        return new Money(amount);
    }

    /** Throws ArithmeticException instead of wrapping around silently. */
    public Money plus(Money other) {
        return new Money(Math.addExact(amount, other.amount));
    }

    public Money times(int quantity) {
        return new Money(Math.multiplyExact(amount, (long) quantity));
    }
}
