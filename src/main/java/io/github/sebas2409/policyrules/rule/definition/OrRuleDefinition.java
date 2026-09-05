package io.github.sebas2409.policyrules.rule.definition;

import java.util.List;
import java.util.Objects;

/**
 * Declarative disjunction: at least one child must match.
 *
 * <p>Compiles to a rule that evaluates its children in declaration order and
 * stops at the first one that matches.</p>
 *
 * @param children non-empty immutable list of child definitions
 */
public record OrRuleDefinition(
        List<RuleDefinition> children
) implements RuleDefinition {

    /**
     * Validates the definition and defensively copies its children.
     *
     * @throws NullPointerException     if the list or one of its children is null
     * @throws IllegalArgumentException if the list is empty
     */
    public OrRuleDefinition {
        children = List.copyOf(
                Objects.requireNonNull(children, "children must not be null")
        );
        if (children.isEmpty()) {
            throw new IllegalArgumentException("OR requires at least one child");
        }
    }
}
