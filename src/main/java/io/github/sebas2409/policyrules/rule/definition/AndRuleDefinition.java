package io.github.sebas2409.policyrules.rule.definition;

import java.util.List;
import java.util.Objects;

/**
 * Declarative conjunction: every child must match.
 *
 * <p>Compiles to a rule that evaluates its children in declaration order and
 * stops at the first one that does not match, so the cheapest or most
 * discriminating child should be listed first.</p>
 *
 * @param children non-empty immutable list of child definitions
 */
public record AndRuleDefinition(
        List<RuleDefinition> children
) implements RuleDefinition {

    /**
     * Validates the definition and defensively copies its children.
     *
     * @throws NullPointerException     if the list or one of its children is null
     * @throws IllegalArgumentException if the list is empty
     */
    public AndRuleDefinition {
        children = List.copyOf(
                Objects.requireNonNull(children, "children must not be null")
        );
        if (children.isEmpty()) {
            throw new IllegalArgumentException("AND requires at least one child");
        }
    }
}
