package io.github.sebas2409.policyrules.rule.definition;

/**
 * Base type for every failure caused by invalid rule configuration.
 *
 * <p>Configuration is data that lives outside the code base (a document in a
 * database, a JSON file, a remote configuration service), so it can be wrong in
 * ways the compiler cannot prevent. Every such failure is reported with this
 * exception or one of its subtypes, which lets an application answer an
 * operator with a precise message instead of a generic error:</p>
 *
 * {@snippet lang = "java":
 * try {
 *     Rule<Order> rule = compiler.compile(definition);
 * } catch (RuleConfigurationException invalidConfiguration) {
 *     // 422: the stored rule is broken, not the incoming request
 *     log.error("Invalid rule configuration", invalidConfiguration);
 * }
 *}
 *
 * <p>It extends {@link IllegalArgumentException} because the offending value is
 * always an argument supplied by the caller, and it is unchecked because a
 * broken configuration is a programming or operational defect rather than an
 * expected outcome of a business operation.</p>
 */
@SuppressWarnings("serial")
public class RuleConfigurationException extends IllegalArgumentException {

    /**
     * Creates an exception with a message.
     *
     * @param message description of the configuration problem
     */
    public RuleConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and an underlying cause.
     *
     * @param message description of the configuration problem
     * @param cause   failure that made the configuration invalid
     */
    public RuleConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
