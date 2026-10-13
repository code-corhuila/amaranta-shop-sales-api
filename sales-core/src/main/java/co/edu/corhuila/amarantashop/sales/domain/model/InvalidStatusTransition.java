package co.edu.corhuila.amarantashop.sales.domain.model;

/** The state rule of the order forbids the change. Answered as {@code 422 INVALID_STATUS_TRANSITION}. */
public class InvalidStatusTransition extends DomainException {

    public InvalidStatusTransition(String ruleId, String message) {
        super(ruleId, message);
    }
}
