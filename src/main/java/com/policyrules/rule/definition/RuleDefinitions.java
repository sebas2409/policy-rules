package com.policyrules.rule.definition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Static factories and inspection helpers for {@link RuleDefinition} trees.
 *
 * <p>The records can always be instantiated directly; these factories exist to
 * keep a hand-written tree readable, which matters in tests and in seed data:</p>
 *
 * {@snippet lang = "java":
 * RuleDefinition definition = RuleDefinitions.and(
 *         RuleDefinitions.atomic("minimum-age", Map.of("minimum", 18)),
 *         RuleDefinitions.or(
 *                 RuleDefinitions.atomic("country-is", Map.of("expected", "ES")),
 *                 RuleDefinitions.atomic("country-is", Map.of("expected", "PT"))
 *         )
 * );
 *}
 *
 * @see RuleDefinitionCodec
 */
public final class RuleDefinitions {

    private RuleDefinitions() {
    }

    /**
     * Creates an atomic definition without parameters.
     *
     * @param type registered rule type name
     * @return the definition
     * @throws NullPointerException     if {@code type} is null
     * @throws IllegalArgumentException if {@code type} is blank
     */
    public static RuleDefinition atomic(String type) {
        return new AtomicRuleDefinition(type);
    }

    /**
     * Creates an atomic definition with parameters.
     *
     * @param type       registered rule type name
     * @param parameters parameters for that type
     * @return the definition
     * @throws NullPointerException     if an argument, key or value is null
     * @throws IllegalArgumentException if {@code type} is blank
     */
    public static RuleDefinition atomic(String type, Map<String, Object> parameters) {
        return new AtomicRuleDefinition(type, parameters);
    }

    /**
     * Creates a conjunction of definitions.
     *
     * @param children child definitions, in evaluation order
     * @return the definition
     * @throws NullPointerException     if the array or one of its elements is null
     * @throws IllegalArgumentException if no child is supplied
     */
    public static RuleDefinition and(RuleDefinition... children) {
        return new AndRuleDefinition(List.of(
                Objects.requireNonNull(children, "children must not be null")
        ));
    }

    /**
     * Creates a conjunction of definitions.
     *
     * @param children child definitions, in evaluation order
     * @return the definition
     * @throws NullPointerException     if the list or one of its elements is null
     * @throws IllegalArgumentException if the list is empty
     */
    public static RuleDefinition and(List<RuleDefinition> children) {
        return new AndRuleDefinition(children);
    }

    /**
     * Creates a disjunction of definitions.
     *
     * @param children child definitions, in evaluation order
     * @return the definition
     * @throws NullPointerException     if the array or one of its elements is null
     * @throws IllegalArgumentException if no child is supplied
     */
    public static RuleDefinition or(RuleDefinition... children) {
        return new OrRuleDefinition(List.of(
                Objects.requireNonNull(children, "children must not be null")
        ));
    }

    /**
     * Creates a disjunction of definitions.
     *
     * @param children child definitions, in evaluation order
     * @return the definition
     * @throws NullPointerException     if the list or one of its elements is null
     * @throws IllegalArgumentException if the list is empty
     */
    public static RuleDefinition or(List<RuleDefinition> children) {
        return new OrRuleDefinition(children);
    }

    /**
     * Creates the negation of a definition.
     *
     * @param child definition whose result is inverted
     * @return the definition
     * @throws NullPointerException if {@code child} is null
     */
    public static RuleDefinition not(RuleDefinition child) {
        return new NotRuleDefinition(child);
    }

    /**
     * Collects every atomic rule type referenced by a definition tree.
     *
     * <p>Combined with {@link RuleRegistry#contains(String)}, this validates
     * stored configuration without compiling it, which is what a
     * configuration-review endpoint or a start-up check usually needs:</p>
     *
     * {@snippet lang = "java":
     * Set<String> missing = new TreeSet<>(RuleDefinitions.typesOf(definition));
     * missing.removeIf(registry::contains);
     * if (!missing.isEmpty()) {
     *     throw new IllegalStateException("Unsupported rule types: " + missing);
     * }
     *}
     *
     * @param definition definition tree to inspect
     * @return immutable set of referenced type names, in encounter order
     * @throws NullPointerException if {@code definition} is null
     */
    public static Set<String> typesOf(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        var types = new LinkedHashSet<String>();
        collectTypes(definition, types);
        return Collections.unmodifiableSet(types);
    }

    /**
     * Counts the nodes of a definition tree, including composites.
     *
     * <p>Handy to reject configuration that grew beyond what the application is
     * willing to evaluate.</p>
     *
     * @param definition definition tree to inspect
     * @return the number of nodes in the tree
     * @throws NullPointerException if {@code definition} is null
     */
    public static int sizeOf(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        return switch (definition) {
            case AtomicRuleDefinition ignored -> 1;
            case NotRuleDefinition not -> 1 + sizeOf(not.child());
            case AndRuleDefinition and -> 1 + sizeOfAll(and.children());
            case OrRuleDefinition or -> 1 + sizeOfAll(or.children());
        };
    }

    private static int sizeOfAll(Collection<RuleDefinition> children) {
        var total = 0;
        for (var child : children) {
            total += sizeOf(child);
        }
        return total;
    }

    private static void collectTypes(RuleDefinition definition, Set<String> types) {
        switch (definition) {
            case AtomicRuleDefinition atomic -> types.add(atomic.type());
            case NotRuleDefinition not -> collectTypes(not.child(), types);
            case AndRuleDefinition and -> and.children().forEach(child -> collectTypes(child, types));
            case OrRuleDefinition or -> or.children().forEach(child -> collectTypes(child, types));
        }
    }
}
