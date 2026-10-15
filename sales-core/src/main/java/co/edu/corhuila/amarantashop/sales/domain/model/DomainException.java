package co.edu.corhuila.amarantashop.sales.domain.model;

/**
 * Base of every error the domain raises. It carries the id of the rule that was broken (INV-ORDER-008...), so
 * a client can branch on it without parsing a message. The domain does not know what a 422 is: the HTTP
 * adapter decides the status, in one place.
 */
public abstract class DomainException extends RuntimeException {

    private final String ruleId;

    protected DomainException(String ruleId, String message) {
        super(message);
        this.ruleId = ruleId;
    }

    public String ruleId() {
        return ruleId;
    }
}
