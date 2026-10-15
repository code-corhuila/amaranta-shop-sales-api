package co.edu.corhuila.amarantashop.sales.domain.model;

/** An invariant of the domain would be broken. Answered as {@code 422 BUSINESS_RULE_VIOLATION}. */
public class BusinessRuleViolation extends DomainException {

    public BusinessRuleViolation(String ruleId, String message) {
        super(ruleId, message);
    }
}
