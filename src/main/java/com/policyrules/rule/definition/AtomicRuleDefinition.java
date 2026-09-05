package com.policyrules.rule.definition;

import java.util.Map;
import java.util.Objects;

/**
 * A leaf of a definition tree: one registered rule type and its parameters.
 *
 * <p>The {@code type} is the contract between the configuration and the code:
 * it selects a {@link RuleFactory} in the {@link RuleRegistry}, and the
 * {@code parameters} are the values that factory reads to build the rule.</p>
 *
 * {@snippet lang = "java":
 * RuleDefinition definition =
 *         new AtomicRuleDefinition("minimum-age", Map.of("minimum", 18));
 *}
 *
 * <p>Parameter values are kept as raw {@link Object} on purpose: they arrive
 * from an external source and their concrete types depend on the driver or
 * parser that produced them ({@code Integer}, {@code Long}, {@code Double},
 * {@code String}, nested {@code List} or {@code Map}). {@link RuleParameters}
 * is what turns those raw values into the types a factory needs.</p>
 *
 * @param type       key used to look up a factory in a {@link RuleRegistry}
 * @param parameters immutable parameters supplied to that factory
 */
public record AtomicRuleDefinition(
        String type,
        Map<String, Object> parameters
) implements RuleDefinition {

    /**
     * Validates the definition and defensively copies its parameters.
     *
     * @throws NullPointerException     if an argument, a parameter key or a
     *                                  parameter value is null
     * @throws IllegalArgumentException if {@code type} is blank
     */
    public AtomicRuleDefinition {
        Objects.requireNonNull(type, "type must not be null");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        parameters = Map.copyOf(
                Objects.requireNonNull(parameters, "parameters must not be null")
        );
    }

    /**
     * Creates a definition for a rule type that takes no parameters.
     *
     * @param type key used to look up a factory in a {@link RuleRegistry}
     */
    public AtomicRuleDefinition(String type) {
        this(type, Map.of());
    }
}
