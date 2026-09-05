package io.github.sebas2409.policyrules.rule.definition;

import io.github.sebas2409.policyrules.rule.Rule;
import io.github.sebas2409.policyrules.rule.Rules;
import io.github.sebas2409.policyrules.rule.trace.ExplainableRule;
import io.github.sebas2409.policyrules.rule.trace.ExplainableRules;

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
 * <h2>Explaining a decision</h2>
 * <p>{@link #compileExplainable(RuleDefinition)} produces the same rule, able
 * to report which node decided an evaluation. It is the compiler, not the
 * registered factories, that names every node, so adding a rule type never
 * involves thinking about traces.</p>
 *
 * @param <T> context type consumed by the produced rules
 * @see RuleRegistry
 * @see RuleDefinition
 * @see ExplainableRule
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

    /**
     * Compiles a definition tree into a rule that can explain its answers.
     *
     * <p>The result is an ordinary {@link Rule}, accepted anywhere a rule is,
     * that additionally reports a {@link io.github.sebas2409.policyrules.rule.trace.RuleTrace}
     * mirroring this definition: one node per operator, one leaf per atomic
     * rule, each labelled with the type and the parameters it was compiled
     * from.</p>
     *
     * {@snippet lang = "java":
     * ExplainableRule<Customer> eligible = compiler.compileExplainable(definition);
     *
     * // On the hot path, nothing changes and nothing is allocated:
     * boolean ok = eligible.matches(customer);
     *
     * // When someone asks why:
     * RuleTrace trace = eligible.explain(customer);
     *}
     *
     * <p>Rule types registered in the {@link RuleRegistry} need no change: the
     * label and the parameters of a leaf come from the definition, not from the
     * factory that built it.</p>
     *
     * @param definition definition loaded from the external source
     * @return the executable rule, able to explain itself
     * @throws NullPointerException       if {@code definition} is null
     * @throws UnknownRuleTypeException   if an atomic type is not registered
     * @throws RuleConfigurationException if a factory rejects its parameters
     */
    public ExplainableRule<T> compileExplainable(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        return switch (definition) {

            case AtomicRuleDefinition atomic -> ExplainableRules.of(
                    atomic.type(),
                    atomic.parameters(),
                    registry.create(atomic.type(), RuleParameters.of(atomic.parameters()))
            );

            case AndRuleDefinition and ->
                    ExplainableRules.and(compileAllExplainable(and.children()));

            case OrRuleDefinition or ->
                    ExplainableRules.or(compileAllExplainable(or.children()));

            case NotRuleDefinition not ->
                    ExplainableRules.not(compileExplainable(not.child()));
        };
    }

    private List<Rule<T>> compileAll(List<RuleDefinition> children) {
        var compiled = new ArrayList<Rule<T>>(children.size());
        for (var child : children) {
            compiled.add(compile(child));
        }
        return compiled;
    }

    private List<ExplainableRule<T>> compileAllExplainable(List<RuleDefinition> children) {
        var compiled = new ArrayList<ExplainableRule<T>>(children.size());
        for (var child : children) {
            compiled.add(compileExplainable(child));
        }
        return compiled;
    }
}
