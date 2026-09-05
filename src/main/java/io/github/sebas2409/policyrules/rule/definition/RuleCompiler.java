package io.github.sebas2409.policyrules.rule.definition;

import io.github.sebas2409.policyrules.rule.Rule;
import io.github.sebas2409.policyrules.rule.Rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a declarative {@link RuleDefinition} into an executable {@link Rule}.
 *
 * <p>Compilation walks the definition tree once and resolves every atomic node
 * through the {@link RuleRegistry}, producing a plain rule that carries no trace
 * of its origin: it is interchangeable with a rule written by hand, and can be
 * used by any policy.</p>
 *
 * {@snippet lang = "java":
 * RuleCompiler<Customer> compiler = new RuleCompiler<>(registry);
 *
 * RuleDefinition definition = ruleStore.load("customer-eligibility");
 * Rule<Customer> eligible = compiler.compile(definition);
 *}
 *
 * <h2>Failures happen here, not during evaluation</h2>
 * <p>Every factory runs while {@link #compile(RuleDefinition)} executes, so an
 * unknown type or an invalid parameter is reported as a
 * {@link RuleConfigurationException} at compile time. Once a rule is compiled,
 * evaluating it cannot fail because of configuration.</p>
 *
 * <h2>Caching</h2>
 * <p>The compiler holds no state beyond its registry and is thread-safe.
 * Compiling is cheap, but not free: an application that reads the same
 * definition on every request should cache the compiled rule and invalidate it
 * when the stored definition changes. Because a compiled rule is immutable, a
 * cached instance can be shared by all threads.</p>
 *
 * @param <T> context type consumed by the produced rules
 * @see RuleRegistry
 * @see RuleDefinition
 */
public final class RuleCompiler<T> {

    /** Catalog of the atomic rule types this compiler can resolve. */
    private final RuleRegistry<T> registry;

    /**
     * Creates a compiler backed by a registry.
     *
     * @param registry registry holding every supported atomic rule type
     * @throws NullPointerException if {@code registry} is null
     */
    public RuleCompiler(RuleRegistry<T> registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Compiles a definition tree into an executable rule.
     *
     * <p>Children of a composite node are evaluated in declaration order and
     * short-circuit, matching the semantics of {@link Rule#and(Rule)} and
     * {@link Rule#or(Rule)}.</p>
     *
     * @param definition definition loaded from the external source
     * @return the executable rule
     * @throws NullPointerException       if {@code definition} is null
     * @throws UnknownRuleTypeException   if an atomic type is not registered
     * @throws RuleConfigurationException if a factory rejects its parameters
     */
    public Rule<T> compile(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        return switch (definition) {

            case AtomicRuleDefinition atomic ->
                    registry.create(atomic.type(), RuleParameters.of(atomic.parameters()));

            case AndRuleDefinition and ->
                    Rules.allOf(compileAll(and.children()));

            case OrRuleDefinition or ->
                    Rules.anyOf(compileAll(or.children()));

            case NotRuleDefinition not ->
                    compile(not.child()).not();
        };
    }

    private List<Rule<T>> compileAll(List<RuleDefinition> children) {
        var compiled = new ArrayList<Rule<T>>(children.size());
        for (var child : children) {
            compiled.add(compile(child));
        }
        return compiled;
    }
}
