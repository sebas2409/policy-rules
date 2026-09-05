package io.github.sebas2409.policyrules.rule.definition;

import java.util.Objects;
import java.util.Set;

/**
 * Signals that a configured rule type has no factory in the {@link RuleRegistry}.
 *
 * <p>This normally means a rule was configured before the release that
 * implements it, or that the type name contains a typo. The exception carries
 * both the requested type and the registered ones so the mismatch can be shown
 * directly to whoever maintains the configuration.</p>
 */
@SuppressWarnings("serial")
public final class UnknownRuleTypeException extends RuleConfigurationException {

    /** Type name that could not be resolved. */
    private final String type;

    /** Immutable, sorted set of type names the registry does know. */
    private final Set<String> knownTypes;

    /**
     * Creates the exception.
     *
     * @param type       unresolved type name
     * @param knownTypes type names currently registered
     * @throws NullPointerException if an argument is null
     */
    public UnknownRuleTypeException(String type, Set<String> knownTypes) {
        super("Unknown rule type '" + Objects.requireNonNull(type, "type must not be null")
                + "'. Registered types: " + Objects.requireNonNull(knownTypes, "knownTypes must not be null"));
        this.type = type;
        this.knownTypes = Set.copyOf(knownTypes);
    }

    /**
     * Returns the type name that could not be resolved.
     *
     * @return unresolved type name
     */
    public String type() {
        return type;
    }

    /**
     * Returns the type names the registry does know.
     *
     * @return immutable set of registered type names
     */
    public Set<String> knownTypes() {
        return knownTypes;
    }
}
