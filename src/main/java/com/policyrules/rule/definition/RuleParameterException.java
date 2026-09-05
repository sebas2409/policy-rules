package com.policyrules.rule.definition;

import java.util.Objects;

/**
 * Signals that a rule parameter is missing or has an unusable value.
 *
 * <p>Thrown by {@link RuleParameters} while a {@link RuleFactory} reads the
 * parameters of an {@link AtomicRuleDefinition}. Because factories read their
 * parameters at build time rather than at evaluation time, this failure surfaces
 * while the rule is compiled and never in the middle of a business decision.</p>
 */
@SuppressWarnings("serial")
public final class RuleParameterException extends RuleConfigurationException {

    /** Name of the parameter that could not be read. */
    private final String parameter;

    /**
     * Creates the exception.
     *
     * @param parameter name of the offending parameter
     * @param message   description of the problem, already including the name
     * @throws NullPointerException if an argument is null
     */
    public RuleParameterException(String parameter, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.parameter = Objects.requireNonNull(parameter, "parameter must not be null");
    }

    /**
     * Returns the name of the parameter that could not be read.
     *
     * @return parameter name
     */
    public String parameter() {
        return parameter;
    }
}
