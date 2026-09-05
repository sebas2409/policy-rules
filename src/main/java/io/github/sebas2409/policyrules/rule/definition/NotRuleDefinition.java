package io.github.sebas2409.policyrules.rule.definition;

import java.util.Objects;

/**
 * Declarative negation of another definition.
 *
 * @param child definition whose result is inverted
 */
public record NotRuleDefinition(
        RuleDefinition child
) implements RuleDefinition {

    /**
     * Validates the child definition.
     *
     * @throws NullPointerException if {@code child} is null
     */
    public NotRuleDefinition {
        Objects.requireNonNull(child, "child must not be null");
    }
}
